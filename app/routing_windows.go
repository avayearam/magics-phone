//go:build windows && amd64

package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"runtime"
	"syscall"
	"time"
	"unsafe"
)

// Experimental: device-to-device WASAPI shared-mode transfer only.
// One input endpoint or one entire render mix -> another render endpoint.
// No process-specific capture, no virtual driver, no app microphone switching.
var (
	iidAudioClient   = syscall.GUID{Data1: 0x1cb9ad4c, Data2: 0xdbfa, Data3: 0x4c32, Data4: [8]byte{0xb1, 0x78, 0xc2, 0xf5, 0x68, 0xa7, 0x03, 0xb2}}
	iidCaptureClient = syscall.GUID{Data1: 0xc8adbd64, Data2: 0xe71e, Data3: 0x48a0, Data4: [8]byte{0xa4, 0xde, 0x18, 0x5c, 0x39, 0x5c, 0xd3, 0x17}}
	iidRenderClient  = syscall.GUID{Data1: 0xf294acfc, Data2: 0x3146, Data3: 0x4483, Data4: [8]byte{0xa7, 0xbf, 0xad, 0xdc, 0xa7, 0xc2, 0x60, 0xe2}}
)

const (
	streamLoopback  = 0x00020000
	streamNoPersist = 0x00080000
	silentFlag      = 0x00000002
)

type waveFormatEx struct {
	FormatTag      uint16
	Channels       uint16
	SamplesPerSec  uint32
	AvgBytesPerSec uint32
	BlockAlign     uint16
	BitsPerSample  uint16
	ExtraSize      uint16
}

func hresult(label string, hr uintptr) error {
	if failed(hr) {
		return fmt.Errorf("%s_hresult_0x%08x", label, uint32(hr))
	}
	return nil
}
func openDevice(enumerator *comObject, id string) (*comObject, error) {
	p, err := syscall.UTF16PtrFromString(id)
	if err != nil {
		return nil, errors.New("bad_device_id")
	}
	var dev *comObject
	hr := enumerator.call(5, uintptr(unsafe.Pointer(p)), uintptr(unsafe.Pointer(&dev)))
	if err := hresult("get_device", hr); err != nil {
		return nil, err
	}
	if dev == nil {
		return nil, errors.New("nil_device")
	}
	return dev, nil
}
func activateAudio(dev *comObject) (*comObject, error) {
	var client *comObject
	hr := dev.call(3, uintptr(unsafe.Pointer(&iidAudioClient)), 23, 0, uintptr(unsafe.Pointer(&client)))
	if err := hresult("activate_audio", hr); err != nil {
		return nil, err
	}
	if client == nil {
		return nil, errors.New("nil_audio_client")
	}
	return client, nil
}
func format(client *comObject) (*waveFormatEx, error) {
	var p *waveFormatEx
	hr := client.call(8, uintptr(unsafe.Pointer(&p)))
	if err := hresult("get_mix_format", hr); err != nil {
		return nil, err
	}
	if p == nil {
		return nil, errors.New("nil_mix_format")
	}
	if p.ExtraSize > 96 || p.BlockAlign == 0 || p.BlockAlign > 128 || p.SamplesPerSec == 0 || p.SamplesPerSec > 384000 {
		return p, errors.New("unsupported_audio_format")
	}
	return p, nil
}
func sameFormat(a, b *waveFormatEx) bool {
	if a == nil || b == nil || a.ExtraSize > 96 || b.ExtraSize > 96 || a.ExtraSize != b.ExtraSize {
		return false
	}
	// WAVEFORMATEX has 18 bytes + cbSize. Compare exact shared-engine layouts.
	aa := unsafe.Slice((*byte)(unsafe.Pointer(a)), 18+int(a.ExtraSize))
	bb := unsafe.Slice((*byte)(unsafe.Pointer(b)), 18+int(b.ExtraSize))
	return bytes.Equal(aa, bb)
}
func service(client *comObject, iid *syscall.GUID, label string) (*comObject, error) {
	var result *comObject
	hr := client.call(14, uintptr(unsafe.Pointer(iid)), uintptr(unsafe.Pointer(&result)))
	if err := hresult(label, hr); err != nil {
		return nil, err
	}
	if result == nil {
		return nil, errors.New("nil_service")
	}
	return result, nil
}
func initialize(client *comObject, flags uint32, f *waveFormatEx, label string) error {
	// Shared mode; 100ms queue; no exclusive-mode or system settings change.
	return hresult(label, client.call(3, 0, uintptr(flags), 1000000, 0, uintptr(unsafe.Pointer(f)), 0))
}
func bufferFrames(client *comObject) (uint32, error) {
	var count uint32
	if err := hresult("buffer_size", client.call(4, uintptr(unsafe.Pointer(&count)))); err != nil {
		return 0, err
	}
	if count == 0 || count > 38400 {
		return 0, errors.New("unsupported_buffer_size")
	}
	return count, nil
}

// The copying function never allocates more than 200ms of queued samples.
// Overflow discards the oldest unplayed audio to bound memory/latency.
func appendBounded(queue []byte, samples []byte, maxBytes int) []byte {
	if len(samples) >= maxBytes {
		return append(queue[:0], samples[len(samples)-maxBytes:]...)
	}
	if len(queue)+len(samples) > maxBytes {
		queue = queue[len(queue)+len(samples)-maxBytes:]
	}
	return append(queue, samples...)
}
func pumpCapture(capClient *comObject, bytesPerFrame int, rate uint32, queue []byte) ([]byte, error) {
	maxBytes := int(rate/5) * bytesPerFrame // 200ms
	for n := 0; n < 64; n++ {
		var frames uint32
		if err := hresult("packet_size", capClient.call(5, uintptr(unsafe.Pointer(&frames)))); err != nil {
			return queue, err
		}
		if frames == 0 {
			return queue, nil
		}
		if frames > 38400 {
			return queue, errors.New("oversized_packet")
		}
		var audio *byte
		var taken, flags uint32
		hr := capClient.call(3, uintptr(unsafe.Pointer(&audio)), uintptr(unsafe.Pointer(&taken)), uintptr(unsafe.Pointer(&flags)), 0, 0)
		if err := hresult("capture_buffer", hr); err != nil {
			return queue, err
		}
		if taken == 0 {
			return queue, errors.New("empty_packet")
		}
		if taken > 38400 || uint64(taken)*uint64(bytesPerFrame) > 8*1024*1024 {
			_ = capClient.call(4, uintptr(taken))
			return queue, errors.New("invalid_packet_length")
		}
		size := int(taken) * bytesPerFrame
		if flags&silentFlag != 0 {
			queue = appendBounded(queue, make([]byte, size), maxBytes)
		} else {
			if audio == nil {
				_ = capClient.call(4, uintptr(taken))
				return queue, errors.New("nil_sample_buffer")
			}
			queue = appendBounded(queue, unsafe.Slice(audio, size), maxBytes)
		}
		if err := hresult("release_capture", capClient.call(4, uintptr(taken))); err != nil {
			return queue, err
		}
	}
	return queue, nil
}
func pumpRender(client, writer *comObject, buffer, block uint32, queue []byte) ([]byte, error) {
	if len(queue) < int(block) {
		return queue, nil
	}
	var padding uint32
	if err := hresult("render_padding", client.call(6, uintptr(unsafe.Pointer(&padding)))); err != nil {
		return queue, err
	}
	if padding > buffer {
		return queue, errors.New("invalid_render_padding")
	}
	free := buffer - padding
	frames := uint32(len(queue) / int(block))
	if frames > free {
		frames = free
	}
	if frames == 0 {
		return queue, nil
	}
	var dst *byte
	if err := hresult("render_buffer", writer.call(3, uintptr(frames), uintptr(unsafe.Pointer(&dst)))); err != nil {
		return queue, err
	}
	if dst == nil {
		_ = writer.call(4, uintptr(frames), 2)
		return queue, errors.New("nil_render_buffer")
	}
	count := int(frames) * int(block)
	copy(unsafe.Slice(dst, count), queue[:count])
	if err := hresult("release_render", writer.call(4, uintptr(frames), 0)); err != nil {
		return queue, err
	}
	return queue[count:], nil
}
func runAudioRoute(ctx context.Context, spec routeSpec, ready chan<- error) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	sent := false
	send := func(err error) {
		if !sent {
			ready <- err
			sent = true
		}
	}
	defer func() {
		if !sent {
			send(errors.New("route_not_initialized"))
		}
	}()
	hr, _, _ := initCOM.Call(0, 0)
	if err := hresult("com_initialize", hr); err != nil {
		send(err)
		return
	}
	defer uninitCOM.Call()
	var enumerator *comObject
	hr, _, _ = createCOM.Call(uintptr(unsafe.Pointer(&clsEnumerator)), 0, 23, uintptr(unsafe.Pointer(&iidEnumerator)), uintptr(unsafe.Pointer(&enumerator)))
	if err := hresult("create_enumerator", hr); err != nil {
		send(err)
		return
	}
	if enumerator == nil {
		send(errors.New("nil_enumerator"))
		return
	}
	defer enumerator.release()
	inputDev, err := openDevice(enumerator, spec.SourceID)
	if err != nil {
		send(err)
		return
	}
	defer inputDev.release()
	outputDev, err := openDevice(enumerator, spec.TargetID)
	if err != nil {
		send(err)
		return
	}
	defer outputDev.release()
	capture, err := activateAudio(inputDev)
	if err != nil {
		send(err)
		return
	}
	defer capture.release()
	render, err := activateAudio(outputDev)
	if err != nil {
		send(err)
		return
	}
	defer render.release()
	sf, err := format(capture)
	if sf != nil {
		defer freeCOM.Call(uintptr(unsafe.Pointer(sf)))
	}
	if err != nil {
		send(err)
		return
	}
	df, err := format(render)
	if df != nil {
		defer freeCOM.Call(uintptr(unsafe.Pointer(df)))
	}
	if err != nil {
		send(err)
		return
	}
	if !sameFormat(sf, df) {
		send(errors.New("audio_format_mismatch_requires_resampling"))
		return
	}
	var closest *waveFormatEx
	support := render.call(7, 0, uintptr(unsafe.Pointer(sf)), uintptr(unsafe.Pointer(&closest)))
	if closest != nil {
		freeCOM.Call(uintptr(unsafe.Pointer(closest)))
	}
	if support != 0 {
		send(errors.New("destination_format_unsupported"))
		return
	}
	flags := uint32(streamNoPersist)
	if spec.SourceKind == "loopback" {
		flags |= streamLoopback
	}
	if err = initialize(capture, flags, sf, "initialize_capture"); err != nil {
		send(err)
		return
	}
	if err = initialize(render, streamNoPersist, sf, "initialize_render"); err != nil {
		send(err)
		return
	}
	reader, err := service(capture, &iidCaptureClient, "capture_service")
	if err != nil {
		send(err)
		return
	}
	defer reader.release()
	writer, err := service(render, &iidRenderClient, "render_service")
	if err != nil {
		send(err)
		return
	}
	defer writer.release()
	capacity, err := bufferFrames(render)
	if err != nil {
		send(err)
		return
	}
	// Prime render stream with silence to avoid initial underrun.
	if capacity > 0 {
		var data *byte
		if err = hresult("prime_render", writer.call(3, uintptr(capacity/2), uintptr(unsafe.Pointer(&data)))); err != nil {
			send(err)
			return
		}
		if err = hresult("release_prime", writer.call(4, uintptr(capacity/2), silentFlag)); err != nil {
			send(err)
			return
		}
	}
	if err = hresult("render_start", render.call(10)); err != nil {
		send(err)
		return
	}
	defer render.call(11)
	if err = hresult("capture_start", capture.call(10)); err != nil {
		send(err)
		return
	}
	defer capture.call(11)
	if ctx.Err() != nil {
		send(ctx.Err())
		return
	}
	send(nil)
	var queue []byte
	ticker := time.NewTicker(8 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			queue, err = pumpCapture(reader, int(sf.BlockAlign), sf.SamplesPerSec, queue)
			if err != nil {
				return
			}
			queue, err = pumpRender(render, writer, capacity, uint32(sf.BlockAlign), queue)
			if err != nil {
				return
			}
		}
	}
}
