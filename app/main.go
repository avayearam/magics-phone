package main

import (
	"context"
	"embed"
	"encoding/json"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

//go:embed web/index.html
var assets embed.FS

const address = "127.0.0.1:18764"

func handler() http.Handler {
	m := newRouteManager(os.Getenv("MAGIC_PHONE_EXPERIMENTAL_AUDIO") == "1", runAudioRoute, discoverAudio)
	return handlerWithManager(discoverAudio, m)
}
func handlerWithDiscovery(provider func() inventory) http.Handler {
	return handlerWithManager(provider, nil)
}
func handlerWithManager(provider func() inventory, m *routeManager) http.Handler {
	discovery := discoveryHandler(provider)
	var routes http.HandlerFunc
	if m != nil {
		routes = routeHandler(m)
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
		if r.Host != address || (r.Header.Get("Origin") != "" && r.Header.Get("Origin") != "http://"+address) || r.Header.Get("Sec-Fetch-Site") == "cross-site" || r.Header.Get("Sec-Fetch-Site") == "same-site" {
			http.Error(w, "Forbidden", 403)
			return
		}
		switch r.URL.Path {
		case "/":
			if r.Method != "GET" {
				http.Error(w, "Method not allowed", 405)
				return
			}
			body, err := assets.ReadFile("web/index.html")
			if err != nil {
				http.Error(w, "Unavailable", 500)
				return
			}
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_, _ = w.Write(body)
		case "/api/status":
			if r.Method != "GET" {
				http.Error(w, "Method not allowed", 405)
				return
			}
			enabled := m != nil && m.enabled
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"version": "0.5.0", "mode": "local-preview", "routingAvailable": enabled, "captureActive": m != nil && routeActive(m)})
		case "/api/discovery":
			if r.Method != "GET" {
				http.Error(w, "Method not allowed", 405)
				return
			}
			discovery(w, r)
		case "/api/route", "/api/control-session", "/api/route/start", "/api/route/stop":
			if m == nil {
				http.NotFound(w, r)
				return
			}
			routes(w, r)
		default:
			http.NotFound(w, r)
		}
	})
}
func routeActive(m *routeManager) bool {
	for _, j := range m.snapshot() {
		if j.Status == "running" || j.Status == "starting" || j.Status == "stopping" {
			return true
		}
	}
	return false
}
func main() {
	m := newRouteManager(os.Getenv("MAGIC_PHONE_EXPERIMENTAL_AUDIO") == "1", runAudioRoute, discoverAudio)
	server := &http.Server{Addr: address, Handler: handlerWithManager(discoverAudio, m), ReadHeaderTimeout: 3 * time.Second, ReadTimeout: 5 * time.Second, WriteTimeout: 6 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 8192}
	ln, err := net.Listen("tcp", address)
	if err != nil {
		log.Fatal("Cannot open local port: ", err)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		limit, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := server.Shutdown(limit); err != nil {
			log.Printf("HTTP stop: %v", err)
		}
	}()
	log.Printf("Magic Phone test panel: http://%s", address)
	go openBrowser("http://" + address + "/")
	err = server.Serve(ln)
	m.stopAll()
	if err != nil && err != http.ErrServerClosed {
		log.Fatal(err)
	}
}
