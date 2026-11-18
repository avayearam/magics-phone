//go:build windows

package main

import (
	"syscall"
	"unsafe"
)

func openBrowser(url string) {
	// Launch the user's standard browser; no shell, scripts, elevation, or remote URL.
	p, err := syscall.UTF16PtrFromString(url)
	if err != nil {
		return
	}
	verb, _ := syscall.UTF16PtrFromString("open")
	shell := syscall.NewLazyDLL("shell32.dll").NewProc("ShellExecuteW")
	shell.Call(0, uintptr(unsafe.Pointer(verb)), uintptr(unsafe.Pointer(p)), 0, 0, 1)
}
