package main

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
)

// A route copies one endpoint's audio stream into a distinct render endpoint.
// Loopback captures the COMPLETE endpoint mix, not a particular application.
type routeSpec struct {
	Slot       string `json:"slot"`
	SourceID   string `json:"sourceId"`
	SourceKind string `json:"sourceKind"`
	TargetID   string `json:"targetId"`
}
type routeInfo struct {
	routeSpec
	Status string `json:"status"`
	Error  string `json:"error,omitempty"`
}
type routeJob struct {
	routeInfo
	cancel context.CancelFunc
	done   chan struct{}
}
type routeRunner func(context.Context, routeSpec, chan<- error)
type routeManager struct {
	mu        sync.Mutex
	jobs      map[string]*routeJob
	enabled   bool
	token     string
	runner    routeRunner
	inventory func() inventory
}

var errUnsupported = errors.New("unsupported_platform")

func newRouteManager(enabled bool, runner routeRunner, provider func() inventory) *routeManager {
	secret := make([]byte, 32)
	if _, err := rand.Read(secret); err != nil {
		panic("Cannot initialize local control security")
	}
	return &routeManager{jobs: make(map[string]*routeJob), enabled: enabled, token: hex.EncodeToString(secret), runner: runner, inventory: provider}
}
func validSpec(s routeSpec, data inventory) error {
	if s.Slot != "A" && s.Slot != "B" {
		return errors.New("invalid_slot")
	}
	if s.SourceKind != "input" && s.SourceKind != "loopback" {
		return errors.New("invalid_source_kind")
	}
	if len(s.SourceID) == 0 || len(s.TargetID) == 0 || len(s.SourceID) > 512 || len(s.TargetID) > 512 || s.SourceID == s.TargetID {
		return errors.New("invalid_endpoints")
	}
	if !data.Available {
		return errors.New("device_inventory_unavailable_or_incomplete")
	}
	sourceDir := "input"
	if s.SourceKind == "loopback" {
		sourceDir = "output"
	}
	sourceOK, targetOK := false, false
	for _, e := range data.Endpoints {
		if e.ID == s.SourceID && e.Direction == sourceDir {
			sourceOK = true
		}
		if e.ID == s.TargetID && e.Direction == "output" {
			targetOK = true
		}
	}
	if !sourceOK || !targetOK {
		return errors.New("endpoint_not_found_or_wrong_direction")
	}
	return nil
}
func (m *routeManager) snapshot() []routeInfo {
	m.mu.Lock()
	defer m.mu.Unlock()
	result := make([]routeInfo, 0, 2)
	for _, slot := range []string{"A", "B"} {
		if job := m.jobs[slot]; job != nil {
			result = append(result, job.routeInfo)
		}
	}
	return result
}
func (m *routeManager) start(spec routeSpec) error {
	if !m.enabled {
		return errors.New("routing_disabled")
	}
	if err := validSpec(spec, m.inventory()); err != nil {
		return err
	}
	m.mu.Lock()
	if old := m.jobs[spec.Slot]; old != nil && (old.Status == "running" || old.Status == "starting" || old.Status == "stopping") {
		m.mu.Unlock()
		return errors.New("slot_busy")
	}
	for _, other := range m.jobs {
		if other.Status != "running" && other.Status != "starting" && other.Status != "stopping" {
			continue
		}
		if other.TargetID == spec.TargetID {
			m.mu.Unlock()
			return errors.New("target_already_in_use")
		}
		if spec.SourceKind == "loopback" && other.SourceKind == "loopback" && spec.SourceID == other.TargetID && spec.TargetID == other.SourceID {
			m.mu.Unlock()
			return errors.New("feedback_cycle")
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	job := &routeJob{routeInfo: routeInfo{routeSpec: spec, Status: "starting"}, cancel: cancel, done: make(chan struct{})}
	m.jobs[spec.Slot] = job
	m.mu.Unlock()
	ready := make(chan error, 1)
	go func() {
		m.runner(ctx, spec, ready)
		m.mu.Lock()
		if m.jobs[spec.Slot] == job {
			if job.Status == "running" {
				job.Status = "failed"
				job.Error = "audio_stream_ended_unexpectedly"
			}
			if job.Status == "starting" {
				job.Status = "failed"
				job.Error = "engine_exited_before_ready"
			}
		}
		m.mu.Unlock()
		close(job.done)
	}()
	timer := time.NewTimer(4 * time.Second)
	defer timer.Stop()
	select {
	case err := <-ready:
		m.mu.Lock()
		if m.jobs[spec.Slot] == job {
			if err != nil {
				job.Status = "failed"
				job.Error = err.Error()
				cancel()
			} else if job.Status == "starting" {
				job.Status = "running"
			}
		}
		m.mu.Unlock()
		return err
	case <-timer.C:
		cancel()
		m.mu.Lock()
		if m.jobs[spec.Slot] == job {
			job.Status = "failed"
			job.Error = "engine_start_timeout"
		}
		m.mu.Unlock()
		return errors.New("engine_start_timeout")
	}
}
func (m *routeManager) stop(slot string) error {
	m.mu.Lock()
	job := m.jobs[slot]
	if job == nil || (job.Status != "starting" && job.Status != "running") {
		m.mu.Unlock()
		return errors.New("route_not_active")
	}
	job.Status = "stopping"
	job.cancel()
	done := job.done
	m.mu.Unlock()
	select {
	case <-done:
		m.mu.Lock()
		if m.jobs[slot] == job {
			job.Status = "stopped"
		}
		m.mu.Unlock()
		return nil
	case <-time.After(3 * time.Second):
		return errors.New("stop_timeout")
	}
}
func (m *routeManager) stopAll() {
	for _, slot := range []string{"A", "B"} {
		_ = m.stop(slot)
	}
}
func (m *routeManager) auth(r *http.Request) bool {
	if r.Header.Get("Origin") != "http://"+address || r.Header.Get("Sec-Fetch-Site") != "same-origin" || r.Header.Get("X-Magic-Control") != "user-action" {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(r.Header.Get("X-Magic-Token")), []byte(m.token)) == 1
}
func routeHandler(m *routeManager) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet && r.URL.Path == "/api/route" {
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"enabled": m.enabled, "routes": m.snapshot()})
			return
		}
		if r.Method == http.MethodGet && r.URL.Path == "/api/control-session" {
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"token": m.token, "enabled": m.enabled})
			return
		}
		if r.Method != http.MethodPost {
			w.Header().Set("Allow", "POST")
			http.Error(w, "Method not allowed", 405)
			return
		}
		if !m.enabled {
			http.Error(w, "Experimental routing is disabled", 403)
			return
		}
		if !m.auth(r) {
			http.Error(w, "Forbidden", 403)
			return
		}
		if r.Header.Get("Content-Type") != "application/json" {
			http.Error(w, "Expected JSON", 415)
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, 2048)
		d := json.NewDecoder(r.Body)
		d.DisallowUnknownFields()
		var err error
		switch r.URL.Path {
		case "/api/route/start":
			var v routeSpec
			if d.Decode(&v) != nil || d.Decode(new(any)) != io.EOF {
				http.Error(w, "Invalid JSON", 400)
				return
			}
			err = m.start(v)
		case "/api/route/stop":
			var v struct {
				Slot string `json:"slot"`
			}
			if d.Decode(&v) != nil || d.Decode(new(any)) != io.EOF || (v.Slot != "A" && v.Slot != "B") {
				http.Error(w, "Invalid JSON", 400)
				return
			}
			err = m.stop(v.Slot)
		default:
			http.NotFound(w, r)
			return
		}
		if err != nil {
			http.Error(w, fmt.Sprintf("route_error: %s", strings.ReplaceAll(err.Error(), "\n", " ")), 409)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "routes": m.snapshot()})
	}
}
