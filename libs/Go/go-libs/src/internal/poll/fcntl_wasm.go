// Copyright 2019 The Go Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

//go:build (js && wasm) || wasip1

package poll

import "syscall"

// fcntl not supported on js/wasm or wasip1/wasm.
func fcntl(fd int, cmd int, arg int) (int, error) {
	return 0, syscall.ENOSYS
}
