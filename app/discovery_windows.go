//go:build windows && amd64

package main

import (
 "path/filepath"
 "runtime"
 "syscall"
 "unsafe"
)

var (
 ole = syscall.NewLazyDLL("ole32.dll")
 initCOM = ole.NewProc("CoInitializeEx")
 uninitCOM = ole.NewProc("CoUninitialize")
 createCOM = ole.NewProc("CoCreateInstance")
 freeCOM = ole.NewProc("CoTaskMemFree")
 clearVariant = ole.NewProc("PropVariantClear")
 kernel = syscall.NewLazyDLL("kernel32.dll")
 openProcess = kernel.NewProc("OpenProcess")
 queryName = kernel.NewProc("QueryFullProcessImageNameW")
 closeHandle = kernel.NewProc("CloseHandle")
 clsEnumerator = syscall.GUID{Data1:0xbcde0395,Data2:0xe52f,Data3:0x467c,Data4:[8]byte{0x8e,0x3d,0xc4,0x57,0x92,0x91,0x69,0x2e}}
 iidEnumerator = syscall.GUID{Data1:0xa95664d2,Data2:0x9614,Data3:0x4f35,Data4:[8]byte{0xa7,0x46,0xde,0x8d,0xb6,0x36,0x17,0xe6}}
 iidSessionManager = syscall.GUID{Data1:0x77aa99a0,Data2:0x1bd6,Data3:0x484f,Data4:[8]byte{0x8b,0xc7,0x2c,0x65,0x4c,0x9a,0x9b,0x6f}}
 iidSessionControl = syscall.GUID{Data1:0xbfb7ff88,Data2:0x7239,Data3:0x4fc9,Data4:[8]byte{0x8f,0xa2,0x07,0xc9,0x50,0xbe,0x9c,0x6d}}
 friendlyKey = propertyKey{syscall.GUID{Data1:0xa45c254e,Data2:0xdf1c,Data3:0x4efd,Data4:[8]byte{0x80,0x20,0x67,0xd1,0x46,0xa8,0x50,0xe0}},14}
)
type propertyKey struct { Format syscall.GUID; ID uint32 }
// PROPVARIANT on Windows x64: type, reserved, union (16 bytes).
type propVariant struct { VT uint16; Reserved [3]uint16; String *uint16; Extra uintptr }
type comObject struct { table *[32]uintptr }
func failed(hr uintptr) bool {return int32(hr)<0}
//go:uintptrescapes
func (o *comObject) call(slot int,args ...uintptr) uintptr {
 a:=append([]uintptr{uintptr(unsafe.Pointer(o))},args...)
 hr,_,_:=syscall.SyscallN(o.table[slot],a...)
 runtime.KeepAlive(o)
 return hr
}
func (o *comObject) release(){if o!=nil{o.call(2)}}
func wideString(p *uint16) string {
 if p==nil{return ""}
 var chars []uint16
 for i:=0;i<32768;i++ { c:=*(*uint16)(unsafe.Add(unsafe.Pointer(p),i*2)); if c==0{break}; chars=append(chars,c) }
 return syscall.UTF16ToString(chars)
}
func endpointInfo(dev *comObject,direction string)(endpoint,bool){
 var id *uint16
 if failed(dev.call(5,uintptr(unsafe.Pointer(&id)))) || id==nil {return endpoint{},false}
 defer freeCOM.Call(uintptr(unsafe.Pointer(id)))
 e:=endpoint{ID:wideString(id),Direction:direction,Name:"Unknown device"}
 if e.ID=="" {return endpoint{},false}
 var store *comObject
 if failed(dev.call(4,0,uintptr(unsafe.Pointer(&store)))) || store==nil {return e,false}
 defer store.release()
 var value propVariant
 // IPropertyStore::GetValue; STGM_READ (0) used above.
 hr:=store.call(5,uintptr(unsafe.Pointer(&friendlyKey)),uintptr(unsafe.Pointer(&value)))
 defer clearVariant.Call(uintptr(unsafe.Pointer(&value)))
 if failed(hr)||value.VT!=31 {return e,false}
 if n:=wideString(value.String);n!="" {e.Name=n;return e,true}
 return e,false
}
func processName(pid uint32) string {
 if pid==0{return "System sounds"}
 h,_,_:=openProcess.Call(0x1000,0,uintptr(pid))
 if h==0{return "Unknown application"}
 defer closeHandle.Call(h)
 buf:=make([]uint16,32768); size:=uint32(len(buf))
 ok,_,_:=queryName.Call(h,0,uintptr(unsafe.Pointer(&buf[0])),uintptr(unsafe.Pointer(&size)))
 if ok==0 || size==0 || size>uint32(len(buf)){return "Unknown application"}
 return filepath.Base(syscall.UTF16ToString(buf[:size]))
}
func scanSession(control *comObject,id string)(audioSession,bool){
 var state uint32
 if failed(control.call(3,uintptr(unsafe.Pointer(&state)))) || state>1 {return audioSession{},false}
 var extended *comObject
 if failed(control.call(0,uintptr(unsafe.Pointer(&iidSessionControl)),uintptr(unsafe.Pointer(&extended)))) || extended==nil {return audioSession{},false}
 defer extended.release()
 var pid uint32
 if failed(extended.call(14,uintptr(unsafe.Pointer(&pid)))) {return audioSession{},false}
 label:="inactive";if state==1{label="active"}
 return audioSession{EndpointID:id,PID:pid,Name:processName(pid),State:label},true
}
func scanSessions(dev *comObject,id string,out *inventory){
 var manager *comObject
 if failed(dev.call(3,uintptr(unsafe.Pointer(&iidSessionManager)),23,0,uintptr(unsafe.Pointer(&manager))))||manager==nil {out.Partial=true;return}
 defer manager.release()
 var list *comObject
 if failed(manager.call(5,uintptr(unsafe.Pointer(&list))))||list==nil {out.Partial=true;return}
 defer list.release()
 var count int32
 if failed(list.call(3,uintptr(unsafe.Pointer(&count))))||count<0 {out.Partial=true;return}
 if count>512 {count=512;out.Partial=true}
 for i:=int32(0);i<count;i++{
  var control *comObject
  if failed(list.call(4,uintptr(i),uintptr(unsafe.Pointer(&control))))||control==nil {out.Partial=true;continue}
  session,ok:=scanSession(control,id);control.release()
  if ok{out.Sessions=append(out.Sessions,session)}
 }
}
func discoverAudio() inventory {
 out:=emptyInventory()
 runtime.LockOSThread();defer runtime.UnlockOSThread()
 hr,_,_:=initCOM.Call(0,0) // COINIT_MULTITHREADED
 if failed(hr){out.Reason="com_init_failed";return out}
 defer uninitCOM.Call()
 var enumerator *comObject
 hr,_,_=createCOM.Call(uintptr(unsafe.Pointer(&clsEnumerator)),0,23,uintptr(unsafe.Pointer(&iidEnumerator)),uintptr(unsafe.Pointer(&enumerator)))
 if failed(hr)||enumerator==nil{out.Reason="enumerator_failed";return out}
 defer enumerator.release()
 for flow,direction:=range []string{"output","input"}{
  var list *comObject
  if failed(enumerator.call(3,uintptr(flow),1,uintptr(unsafe.Pointer(&list))))||list==nil {out.Partial=true;continue}
  var count uint32
  if failed(list.call(3,uintptr(unsafe.Pointer(&count)))){out.Partial=true;list.release();continue}
  out.Available=true
  if count>128{count=128;out.Partial=true}
  for i:=uint32(0);i<count;i++{
   var dev *comObject
   if failed(list.call(4,uintptr(i),uintptr(unsafe.Pointer(&dev))))||dev==nil{out.Partial=true;continue}
   e,ok:=endpointInfo(dev,direction)
   if !ok{out.Partial=true}
   if e.ID!=""{out.Endpoints=append(out.Endpoints,e);scanSessions(dev,e.ID,&out)}
   dev.release()
  }
  list.release()
 }
 if !out.Available{out.Reason="enumeration_failed"}
 return out
}
