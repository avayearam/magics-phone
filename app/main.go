package main

import (
 "embed"
 "fmt"
 "log"
 "net/http"
 "time"
)

//go:embed web/index.html
var assets embed.FS

const address = "127.0.0.1:18764"

func handler() http.Handler { return handlerWithDiscovery(discoverAudio) }

func handlerWithDiscovery(provider func() inventory) http.Handler {
 discovery := discoveryHandler(provider)
 return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
  w.Header().Set("Cache-Control", "no-store")
  w.Header().Set("X-Content-Type-Options", "nosniff")
  w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
  if r.Host != address || r.Header.Get("Origin") != "" || r.Header.Get("Sec-Fetch-Site") == "cross-site" { http.Error(w, "Forbidden", 403); return }
  if r.Method != http.MethodGet { w.Header().Set("Allow", "GET"); http.Error(w, "Method not allowed", 405); return }
  switch r.URL.Path {
  case "/":
   body, err := assets.ReadFile("web/index.html"); if err != nil { http.Error(w,"Unavailable",500); return }
   w.Header().Set("Content-Type", "text/html; charset=utf-8"); _, _ = w.Write(body)
  case "/api/status":
   w.Header().Set("Content-Type", "application/json")
   _, _ = fmt.Fprint(w, `{"version":"0.2.0","mode":"local-preview","routingAvailable":false,"captureActive":false}`)
  case "/api/discovery":
   discovery(w,r)
  default: http.NotFound(w,r)
  }
 })
}

func main() {
 server := &http.Server{Addr: address, Handler: handler(), ReadHeaderTimeout: 3*time.Second, ReadTimeout: 5*time.Second, WriteTimeout: 5*time.Second, IdleTimeout: 30*time.Second, MaxHeaderBytes: 8192}
 log.Printf("Magic Phone local preview: http://%s", address)
 log.Fatal(server.ListenAndServe())
}
