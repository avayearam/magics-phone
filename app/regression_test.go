package main

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestLegacyRouteLifecycleAndCycle(t *testing.T) {
	provider := safetyInventory
	m := newRouteManager(true, func(ctx context.Context, _ routeSpec, ready chan<- error) { ready <- nil; <-ctx.Done() }, provider)
	a := routeSpec{Slot: "A", SourceKind: "loopback", SourceID: "renderA", TargetID: "renderB"}
	if err := m.start(a); err != nil {
		t.Fatal(err)
	}
	if err := m.start(a); err == nil {
		t.Fatal("duplicate route allowed")
	}
	inverse := routeSpec{Slot: "B", SourceKind: "loopback", SourceID: "renderB", TargetID: "renderA"}
	if err := m.start(inverse); err == nil || err.Error() != "feedback_cycle" {
		t.Fatalf("expected cycle guard, got: %v", err)
	}
	if err := m.start(safetySpec("B", "renderB")); err == nil || err.Error() != "target_already_in_use" {
		t.Fatal("shared target accepted")
	}
	if err := m.start(safetySpec("B", "renderC")); err != nil {
		t.Fatal(err)
	}
	if len(m.snapshot()) != 2 {
		t.Fatal("two route slots missing")
	}
	m.stopAll()
	for _, r := range m.snapshot() {
		if r.Status != "stopped" {
			t.Fatalf("stopAll left %s", r.Status)
		}
	}
	if err := m.start(a); err != nil {
		t.Fatal("restart after full exit", err)
	}
	m.stopAll()
}
func TestLegacyRouteHTTPGuards(t *testing.T) {
	m := newRouteManager(true, func(ctx context.Context, _ routeSpec, ready chan<- error) { ready <- nil; <-ctx.Done() }, safetyInventory)
	h := handlerWithManager(safetyInventory, m)
	data := `{"slot":"A","sourceId":"input","sourceKind":"input","targetId":"renderA"}`
	for _, tc := range []struct {
		origin, site, token, control, ct, body string
		expect                                 int
	}{
		{"https://example.com", "cross-site", m.token, "user-action", "application/json", data, 403},
		{"http://" + address, "same-origin", "", "user-action", "application/json", data, 403},
		{"http://" + address, "same-origin", m.token, "", "application/json", data, 403},
		{"http://" + address, "same-origin", m.token, "user-action", "text/plain", data, 415},
		{"http://" + address, "same-origin", m.token, "user-action", "application/json", data + `{}`, 400},
		{"http://" + address, "same-origin", m.token, "user-action", "application/json", strings.Replace(data, `"slot"`, `"unexpected":0,"slot"`, 1), 400},
		{"http://" + address, "same-origin", m.token, "user-action", "application/json", data, 200},
	} {
		r := httptest.NewRequest("POST", "http://"+address+"/api/route/start", bytes.NewBufferString(tc.body))
		r.Header.Set("Origin", tc.origin)
		r.Header.Set("Sec-Fetch-Site", tc.site)
		r.Header.Set("X-Magic-Token", tc.token)
		r.Header.Set("X-Magic-Control", tc.control)
		r.Header.Set("Content-Type", tc.ct)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != tc.expect {
			t.Fatalf("unexpected HTTP %d want %d response=%s", w.Code, tc.expect, w.Body.String())
		}
	}
	m.stopAll()
}
func TestLegacyDefaultAgentNoCapture(t *testing.T) {
	t.Setenv("MAGIC_PHONE_EXPERIMENTAL_AUDIO", "")
	h := handler()
	for _, tc := range []struct {
		method, host, origin, site, path string
		want                             int
	}{
		{"GET", address, "", "same-origin", "/api/status", 200},
		{"GET", address, "", "none", "/", 200},
		{"POST", address, "", "same-origin", "/api/status", 405},
		{"GET", "bad.example", "", "same-origin", "/api/status", 403},
		{"GET", address, "https://bad.example", "cross-site", "/api/status", 403},
		{"GET", address, "", "same-origin", "/api/nonexistent", 404},
	} {
		r := httptest.NewRequest(tc.method, "http://"+tc.host+tc.path, nil)
		r.Header.Set("Origin", tc.origin)
		r.Header.Set("Sec-Fetch-Site", tc.site)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != tc.want {
			t.Fatalf("%s: HTTP %d want %d", tc.path, w.Code, tc.want)
		}
		if tc.path == "/api/status" && w.Code == 200 {
			var status struct {
				RoutingAvailable bool
				CaptureActive    bool
			}
			if err := json.Unmarshal(w.Body.Bytes(), &status); err != nil || status.RoutingAvailable || status.CaptureActive {
				t.Fatalf("unsafe default status %s", w.Body.String())
			}
		}
	}
}
func TestLegacyInvalidEndpoints(t *testing.T) {
	d := safetyInventory()
	ok := safetySpec("A", "renderA")
	if err := validSpec(ok, d); err != nil {
		t.Fatal(err)
	}
	for _, modify := range []func(*routeSpec){func(s *routeSpec) { s.Slot = "X" }, func(s *routeSpec) { s.SourceKind = "bad" }, func(s *routeSpec) { s.SourceID = "missing" }, func(s *routeSpec) { s.TargetID = "missing" }, func(s *routeSpec) { s.SourceID = s.TargetID }} {
		v := ok
		modify(&v)
		if err := validSpec(v, d); err == nil {
			t.Fatalf("accepted bad route %+v", v)
		}
	}
}
func TestRoutingDisabledByDefault(t *testing.T) {
	m := newRouteManager(false, func(ctx context.Context, _ routeSpec, ready chan<- error) { ready <- nil }, safetyInventory)
	if err := m.start(safetySpec("A", "renderA")); err == nil {
		t.Fatal("disabled route started")
	}
}
func TestOriginVerificationBlocksCrossSite(t *testing.T) {
	m := newRouteManager(true, func(ctx context.Context, _ routeSpec, ready chan<- error) { ready <- nil }, safetyInventory)
	h := handlerWithManager(safetyInventory, m)
	r := httptest.NewRequest(http.MethodGet, "http://"+address+"/api/route", nil)
	r.Header.Set("Sec-Fetch-Site", "cross-site")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 403 {
		t.Fatalf("cross site GET allowed %d", w.Code)
	}
}
