//go:build !windows || !amd64

package main

import "context"

func runAudioRoute(ctx context.Context, spec routeSpec, ready chan<- error) { ready <- errUnsupported }
