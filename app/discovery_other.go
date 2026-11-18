//go:build !windows || !amd64

package main

func discoverAudio() inventory {
 result:=emptyInventory()
 result.Reason="unsupported_platform"
 return result
}
