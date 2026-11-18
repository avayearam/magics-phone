package main

import (
	"context"
	"errors"
	"testing"
	"time"
)

func safetyInventory() inventory {
	return inventory{Available: true, Endpoints: []endpoint{{ID: "input", Direction: "input"}, {ID: "renderA", Direction: "output"}, {ID: "renderB", Direction: "output"}, {ID: "renderC", Direction: "output"}}}
}
func safetySpec(slot, target string) routeSpec {
	return routeSpec{Slot: slot, SourceID: "input", SourceKind: "input", TargetID: target}
}

func TestTimedOutWorkerCannotBeReplacedOrShareTarget(t *testing.T) {
	release := make(chan struct{})
	started := make(chan struct{})
	exited := make(chan struct{})
	runner := func(ctx context.Context, _ routeSpec, _ chan<- error) { close(started); <-release; close(exited) }
	m := newRouteManager(true, runner, safetyInventory)
	m.startupTimeout = 20 * time.Millisecond
	if err := m.start(safetySpec("A", "renderA")); err == nil || err.Error() != "engine_start_timeout" {
		t.Fatalf("want timeout: %v", err)
	}
	<-started
	if err := m.start(safetySpec("A", "renderB")); err == nil || err.Error() != "slot_busy" {
		t.Fatalf("expected busy slot: %v", err)
	}
	if err := m.start(safetySpec("B", "renderA")); err == nil || err.Error() != "target_already_in_use" {
		t.Fatalf("target must be reserved: %v", err)
	}
	close(release)
	<-exited
	// Wait until supervisor closes the completed worker's done channel.
	for deadline := time.Now().Add(time.Second); time.Now().Before(deadline); time.Sleep(time.Millisecond) {
		m.mu.Lock()
		j := m.jobs["A"]
		select {
		case <-j.done:
			m.mu.Unlock()
			goto exitedOk
		default:
			m.mu.Unlock()
		}
	}
	t.Fatal("worker did not exit")
exitedOk:
	m.runner = func(ctx context.Context, _ routeSpec, ready chan<- error) { ready <- nil; <-ctx.Done() }
	if err := m.start(safetySpec("A", "renderB")); err != nil {
		t.Fatal(err)
	}
	if err := m.stop("A"); err != nil {
		t.Fatal(err)
	}
}
func TestLateReadinessDoesNotReportSuccessfulStart(t *testing.T) {
	release := make(chan struct{})
	m := newRouteManager(true, func(ctx context.Context, _ routeSpec, ready chan<- error) { <-release; ready <- nil; <-ctx.Done() }, safetyInventory)
	m.startupTimeout = 10 * time.Millisecond
	if err := m.start(safetySpec("A", "renderA")); err == nil {
		t.Fatal("timeout required")
	}
	close(release)
	if err := m.stop("A"); err != nil {
		t.Fatal(err)
	}
	if m.snapshot()[0].Status != "failed" {
		t.Fatal("cancelled startup must remain failed")
	}
}
func TestStopFailedJobCancelsAndPreservesFailure(t *testing.T) {
	ch := make(chan struct{})
	m := newRouteManager(true, func(ctx context.Context, _ routeSpec, ready chan<- error) { close(ch); <-ctx.Done() }, safetyInventory)
	m.startupTimeout = 10 * time.Millisecond
	_ = m.start(safetySpec("A", "renderA"))
	<-ch
	if err := m.stop("A"); err != nil {
		t.Fatal(err)
	}
	if got := m.snapshot()[0]; got.Status != "failed" || got.Error != "engine_start_timeout" {
		t.Fatal(got)
	}
}
func TestFailedRunnerStartRemainsFailure(t *testing.T) {
	m := newRouteManager(true, func(ctx context.Context, _ routeSpec, ready chan<- error) { ready <- errors.New("test_start_failure") }, safetyInventory)
	if err := m.start(safetySpec("A", "renderA")); err == nil {
		t.Fatal("failed runner started")
	}
	if got := m.snapshot()[0]; got.Status != "failed" {
		t.Fatal(got)
	}
}
