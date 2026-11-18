package main

import (
 "encoding/json"
 "net/http/httptest"
 "testing"
)
func TestDiscovery(t *testing.T){
 calls:=0
 h:=handlerWithDiscovery(func() inventory{calls++;return inventory{Available:true,Endpoints:[]endpoint{{ID:"test",Name:"Test output",Direction:"output"}},Sessions:[]audioSession{{EndpointID:"test",PID:42,Name:"test.exe",State:"active"}}}})
 for _,c:=range []struct{method,host,site,origin string;code int;called bool}{
  {"GET",address,"same-origin","",200,true},
  {"GET",address,"same-site","",403,false},
  {"GET",address,"cross-site","",403,false},
  {"GET",address,"","https://example.org",403,false},
  {"GET","evil.example","","",403,false},
  {"POST",address,"same-origin","",405,false},
 }{
  before:=calls;r:=httptest.NewRequest(c.method,"http://"+c.host+"/api/discovery",nil);r.Header.Set("Sec-Fetch-Site",c.site);r.Header.Set("Origin",c.origin);w:=httptest.NewRecorder();h.ServeHTTP(w,r)
  if w.Code!=c.code||(calls>before)!=c.called{t.Fatalf("%+v: HTTP %d calls %d",c,w.Code,calls)}
  if c.called{var data inventory;if json.Unmarshal(w.Body.Bytes(),&data)!=nil||!data.Available||len(data.Endpoints)!=1||len(data.Sessions)!=1{t.Fatal("invalid inventory")}}
  if w.Header().Get("Access-Control-Allow-Origin")!=""{t.Fatal("CORS exposed")}
 }
}
func TestDiscoveryUnavailable(t *testing.T){
 h:=handlerWithDiscovery(func() inventory{return inventory{Reason:"unsupported_platform"}})
 r:=httptest.NewRequest("GET","http://"+address+"/api/discovery",nil);w:=httptest.NewRecorder();h.ServeHTTP(w,r)
 var data inventory;if json.Unmarshal(w.Body.Bytes(),&data)!=nil||data.Available||data.Endpoints==nil||data.Sessions==nil||data.Reason!="unsupported_platform"{t.Fatal(w.Body.String())}
}
func TestDiscoveryBusy(t *testing.T){
 entered:=make(chan struct{});done:=make(chan struct{});release:=make(chan struct{})
 h:=handlerWithDiscovery(func() inventory{close(entered);<-release;return emptyInventory()})
 req:=func()*httptest.ResponseRecorder{w:=httptest.NewRecorder();h.ServeHTTP(w,httptest.NewRequest("GET","http://"+address+"/api/discovery",nil));return w}
 go func(){defer close(done);req()}();<-entered
 if w:=req();w.Code!=429{close(release);<-done;t.Fatal(w.Code)}
 close(release);<-done
}
