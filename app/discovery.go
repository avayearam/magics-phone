package main

import (
 "encoding/json"
 "net/http"
)

type endpoint struct {
 ID string `json:"id"`
 Name string `json:"name"`
 Direction string `json:"direction"`
}
type audioSession struct {
 EndpointID string `json:"endpointId"`
 PID uint32 `json:"pid"`
 Name string `json:"name"`
 State string `json:"state"`
}
type inventory struct {
 Available bool `json:"available"`
 Partial bool `json:"partial"`
 Reason string `json:"reason,omitempty"`
 Endpoints []endpoint `json:"endpoints"`
 Sessions []audioSession `json:"sessions"`
}
func emptyInventory() inventory { return inventory{Endpoints:[]endpoint{}, Sessions:[]audioSession{}} }
func discoveryHandler(provider func() inventory) http.HandlerFunc {
 busy := make(chan struct{},1)
 return func(w http.ResponseWriter,r *http.Request) {
  if site:=r.Header.Get("Sec-Fetch-Site"); site!="" && site!="none" && site!="same-origin" { http.Error(w,"Forbidden",403); return }
  select { case busy<-struct{}{}: defer func(){<-busy}(); default: http.Error(w,"Discovery busy",429); return }
  result:=provider()
  if result.Endpoints==nil {result.Endpoints=[]endpoint{}}
  if result.Sessions==nil {result.Sessions=[]audioSession{}}
  w.Header().Set("Content-Type","application/json")
  _=json.NewEncoder(w).Encode(result)
 }
}
