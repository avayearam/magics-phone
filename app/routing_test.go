package main

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func demoInventory() inventory {
	return inventory{Available: true, Endpoints: []endpoint{{ID: "in1", Direction: "input", Name: "Mic"}, {ID: "in2", Direction: "input"}, {ID: "out1", Direction: "output"}, {ID: "out2", Direction: "output"}, {ID: "out3", Direction: "output"}}, Sessions: []audioSession{}}
}
func goodRunner(ctx context.Context, _ routeSpec, ready chan<- error) { ready <- nil; <-ctx.Done() }
func TestRouteValidation(t *testing.T) {
	d := demoInventory()
	ok := routeSpec{Slot: "A", SourceID: "in1", SourceKind: "input", TargetID: "out1"}
	if err := validSpec(ok, d); err != nil {
		t.Fatal(err)
	}
	for _, change := range []func(*routeSpec){
		func(s *routeSpec) { s.Slot = "X" }, func(s *routeSpec) { s.SourceKind = "unknown" }, func(s *routeSpec) { s.SourceID = "out2" }, func(s *routeSpec) { s.TargetID = "in2" }, func(s *routeSpec) { s.SourceID = "" }, func(s *routeSpec) { s.SourceID = s.TargetID },
	} {
		s := ok
		change(&s)
		if validSpec(s, d) == nil {
			t.Fatalf("accepted unsafe spec %+v", s)
		}
	}
	d.Available = false
	if validSpec(ok, d) == nil {
		t.Fatal("accepted missing inventory")
	}
}
func TestRouteLifecycleAndCycle(t *testing.T) {
	m := newRouteManager(true, goodRunner, demoInventory)
	a := routeSpec{Slot: "A", SourceID: "out1", SourceKind: "loopback", TargetID: "out2"}
	if err := m.start(a); err != nil {
		t.Fatal(err)
	}
	if err := m.start(a); err == nil {
		t.Fatal("duplicate start")
	}
	if err := m.start(routeSpec{Slot: "B", SourceID: "out2", SourceKind: "loopback", TargetID: "out1"}); err == nil {
		t.Fatal("feedback cycle accepted")
	}
	if err := m.start(routeSpec{Slot: "B", SourceID: "in1", SourceKind: "input", TargetID: "out2"}); err == nil {
		t.Fatal("target conflict accepted")
	}
	if err := m.start(routeSpec{Slot: "B", SourceID: "in1", SourceKind: "input", TargetID: "out3"}); err != nil {
		t.Fatal(err)
	}
	if got := m.snapshot(); len(got) != 2 || got[0].Status != "running" || got[1].Status != "running" {
		t.Fatalf("bad state: %+v", got)
	}
	if err := m.stop("A"); err != nil {
		t.Fatal(err)
	}
	if err := m.stop("B"); err != nil {
		t.Fatal(err)
	}
	if m.stop("A") == nil {
		t.Fatal("double stop accepted")
	}
	if err := m.start(a); err != nil {
		t.Fatal("cannot restart", err)
	}
	if err := m.stop("A"); err != nil {
		t.Fatal(err)
	}
}
func TestFailedRouteNeverReportedRunning(t *testing.T) {
	m := newRouteManager(true, func(_ context.Context, _ routeSpec, ready chan<- error) { ready <- context.DeadlineExceeded }, demoInventory)
	if err := m.start(routeSpec{Slot: "A", SourceID: "in1", SourceKind: "input", TargetID: "out1"}); err == nil {
		t.Fatal("failed route started")
	}
	if got := m.snapshot()[0]; got.Status != "failed" {
		t.Fatalf("%+v", got)
	}
}
func TestUnexpectedExitIsFailure(t *testing.T) {
	exiting := make(chan struct{})
	m := newRouteManager(true, func(_ context.Context, _ routeSpec, ready chan<- error) { ready <- nil; <-exiting }, demoInventory)
	if err := m.start(routeSpec{Slot: "A", SourceID: "in1", SourceKind: "input", TargetID: "out1"}); err != nil {
		t.Fatal(err)
	}
	close(exiting)
	deadline := time.After(time.Second)
	for {
		if got := m.snapshot()[0]; got.Status == "failed" {
			break
		}
		select {
		case <-deadline:
			t.Fatal("unexpected end not surfaced")
		default:
			time.Sleep(time.Millisecond)
		}
	}
}
func TestSecurityForRouteHTTP(t *testing.T) {
	var count atomic.Int32
	runner := func(ctx context.Context, _ routeSpec, ready chan<- error) { count.Add(1); ready <- nil; <-ctx.Done() }
	m := newRouteManager(true, runner, demoInventory)
	h := handlerWithManager(demoInventory, m)
	send := func(path, origin, site, token, control, ct, body string) int {
		req := httptest.NewRequest("POST", "http://"+address+path, bytes.NewBufferString(body))
		req.Header.Set("Origin", origin)
		req.Header.Set("Sec-Fetch-Site", site)
		req.Header.Set("X-Magic-Token", token)
		req.Header.Set("X-Magic-Control", control)
		req.Header.Set("Content-Type", ct)
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		return rec.Code
	}
	spec := `{"slot":"A","sourceId":"in1","sourceKind":"input","targetId":"out1"}`
	for _, tc := range []struct {
		name, origin, site, token, control, ct, body string
		code                                         int
	}{
		{"cross origin", "https://evil.example", "cross-site", m.token, "user-action", "application/json", spec, 403},
		{"no token", "http://" + address, "same-origin", "", "user-action", "application/json", spec, 403},
		{"no activation marker", "http://" + address, "same-origin", m.token, "", "application/json", spec, 403},
		{"wrong type", "http://" + address, "same-origin", m.token, "user-action", "text/plain", spec, 415},
		{"extra json", "http://" + address, "same-origin", m.token, "user-action", "application/json", spec + `{}`, 400},
		{"unknown property", "http://" + address, "same-origin", m.token, "user-action", "application/json", strings.Replace(spec, `"slot"`, `"surprise":3,"slot"`, 1), 400},
		{"valid", "http://" + address, "same-origin", m.token, "user-action", "application/json", spec, 200},
	} {
		t.Run(tc.name, func(t *testing.T) {
			code := send("/api/route/start", tc.origin, tc.site, tc.token, tc.control, tc.ct, tc.body)
			if code != tc.code {
				t.Fatalf("got %d want %d", code, tc.code)
			}
		})
	}
	if count.Load() != 1 {
		t.Fatal("did not enforce explicit consent / unique start")
	}
	if code := send("/api/route/stop", "http://"+address, "same-origin", m.token, "user-action", "application/json", `{"slot":"A"}`); code != 200 {
		t.Fatalf("stop %d", code)
	}
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest("GET", "http://"+address+"/api/route", nil))
	if rr.Code != 200 {
		t.Fatal(rr.Code)
	}
	var data struct{ Routes []routeInfo }
	if err := json.Unmarshal(rr.Body.Bytes(), &data); err != nil || len(data.Routes) != 1 || data.Routes[0].Status != "stopped" {
		t.Fatal(rr.Body.String())
	}
}
func TestDisabledCannotActivate(t *testing.T) {
	m := newRouteManager(false, goodRunner, demoInventory)
	if m.start(routeSpec{Slot: "A", SourceID: "in1", SourceKind: "input", TargetID: "out1"}) == nil {
		t.Fatal("disabled started")
	}
}
func TestNoAudioOnInvalidInput(t *testing.T) {
	var count atomic.Int32
	m := newRouteManager(true, func(ctx context.Context, s routeSpec, ready chan<- error) { count.Add(1); ready <- nil; <-ctx.Done() }, demoInventory)
	if err := m.start(routeSpec{Slot: "A", SourceID: "invalid", SourceKind: "input", TargetID: "out1"}); err == nil {
		t.Fatal("accepted unknown device")
	}
	if count.Load() != 0 {
		t.Fatal("ran engine despite invalid input")
	}
}
func TestWrongMethodDoesNotReadSecret(t *testing.T) {
	m := newRouteManager(true, goodRunner, demoInventory)
	h := handlerWithManager(demoInventory, m)
	req := httptest.NewRequest(http.MethodGet, "http://evil.example/api/control-session", nil)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	if w.Code != 403 || strings.Contains(w.Body.String(), m.token) {
		t.Fatal("token leaked")
	}
}
