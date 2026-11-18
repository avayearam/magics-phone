package main

import (
 "net/http/httptest"
 "testing"
 "encoding/json"
)

func TestHandler(t *testing.T) {
 cases := []struct{ name, method, host, origin, site, path string; code int }{
  {"status","GET",address,"","same-origin","/api/status",200},
  {"page","GET",address,"","none","/",200},
  {"foreign host","GET","example.com","","","/api/status",403},
  {"foreign origin","GET",address,"https://example.com","","/api/status",403},
  {"cross site","GET",address,"","cross-site","/api/status",403},
  {"write blocked","POST",address,"","same-origin","/api/status",405},
  {"unknown action","GET",address,"","same-origin","/api/routing/start",404},
 }
 for _, c := range cases { t.Run(c.name,func(t *testing.T){
  r:=httptest.NewRequest(c.method,"http://"+c.host+c.path,nil); r.Header.Set("Origin",c.origin); r.Header.Set("Sec-Fetch-Site",c.site)
  w:=httptest.NewRecorder();handler().ServeHTTP(w,r)
  if w.Code!=c.code {t.Fatalf("got %d want %d",w.Code,c.code)}
  if c.name=="status" { var status struct{RoutingAvailable bool; CaptureActive bool}; if json.Unmarshal(w.Body.Bytes(),&status)!=nil || status.RoutingAvailable || status.CaptureActive {t.Fatal("unsafe status")} }
  if w.Header().Get("Access-Control-Allow-Origin")!="" {t.Fatal("unexpected CORS")}
 }) }
}
