// Copyright 2023 The Go Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package loopvar_test

import (
	"internal/testenv"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

type testcase struct {
	lvFlag      string // ==-2, -1, 0, 1, 2
	buildExpect string // message, if any
	expectRC    int
	files       []string
}

var for_files = []string{
	"for_esc_address.go",             // address of variable
	"for_esc_closure.go",             // closure of variable
	"for_esc_minimal_closure.go",     // simple closure of variable
	"for_esc_method.go",              // method value of variable
	"for_complicated_esc_address.go", // modifies loop index in body
}

var range_files = []string{
	"range_esc_address.go",         // address of variable
	"range_esc_closure.go",         // closure of variable
	"range_esc_minimal_closure.go", // simple closure of variable
	"range_esc_method.go",          // method value of variable
}

var cases = []testcase{
	{"-1", "", 11, for_files[:1]},
	{"0", "", 0, for_files[:1]},
	{"1", "", 0, for_files[:1]},
	{"2", "transformed loop variable i ", 0, for_files},

	{"-1", "", 11, range_files[:1]},
	{"0", "", 0, range_files[:1]},
	{"1", "", 0, range_files[:1]},
	{"2", "transformed loop variable i ", 0, range_files},

	{"1", "", 0, []string{"for_nested.go"}},
}

// TestLoopVar checks that the GOEXPERIMENT and debug flags behave as expected.
func TestLoopVar(t *testing.T) {
	switch runtime.GOOS {
	case "linux", "darwin":
	default:
		t.Skipf("Slow test, usually avoid it, os=%s not linux or darwin", runtime.GOOS)
	}
	switch runtime.GOARCH {
	case "amd64", "arm64":
	default:
		t.Skipf("Slow test, usually avoid it, arch=%s not amd64 or arm64", runtime.GOARCH)
	}

	testenv.MustHaveGoBuild(t)
	gocmd := testenv.GoToolPath(t)
	tmpdir := t.TempDir()
	output := filepath.Join(tmpdir, "foo.exe")

	for i, tc := range cases {
		for _, f := range tc.files {
			source := f
			cmd := testenv.Command(t, gocmd, "build", "-o", output, "-gcflags=-d=loopvar="+tc.lvFlag, source)
			cmd.Env = append(cmd.Env, "GOEXPERIMENT=loopvar", "HOME="+tmpdir)
			cmd.Dir = "testdata"
			t.Logf("File %s loopvar=%s expect '%s' exit code %d", f, tc.lvFlag, tc.buildExpect, tc.expectRC)
			b, e := cmd.CombinedOutput()
			if e != nil {
				t.Error(e)
			}
			if tc.buildExpect != "" {
				s := string(b)
				if !strings.Contains(s, tc.buildExpect) {
					t.Errorf("File %s test %d expected to match '%s' with \n-----\n%s\n-----", f, i, tc.buildExpect, s)
				}
			}
			// run what we just built.
			cmd = testenv.Command(t, output)
			b, e = cmd.CombinedOutput()
			if tc.expectRC != 0 {
				if e == nil {
					t.Errorf("Missing expected error, file %s, case %d", f, i)
				} else if ee, ok := (e).(*exec.ExitError); !ok || ee.ExitCode() != tc.expectRC {
					t.Error(e)
				} else {
					// okay
				}
			} else if e != nil {
				t.Error(e)
			}
		}
	}
}

func TestLoopVarInlines(t *testing.T) {
	switch runtime.GOOS {
	case "linux", "darwin":
	default:
		t.Skipf("Slow test, usually avoid it, os=%s not linux or darwin", runtime.GOOS)
	}
	switch runtime.GOARCH {
	case "amd64", "arm64":
	default:
		t.Skipf("Slow test, usually avoid it, arch=%s not amd64 or arm64", runtime.GOARCH)
	}

	testenv.MustHaveGoBuild(t)
	gocmd := testenv.GoToolPath(t)
	tmpdir := t.TempDir()

	root := "cmd/compile/internal/loopvar/testdata/inlines"

	f := func(pkg string) string {
		// This disables the loopvar change, except for the specified package.
		// The effect should follow the package, even though everything (except "c")
		// is inlined.
		cmd := testenv.Command(t, gocmd, "run", "-gcflags="+pkg+"=-d=loopvar=1", root)
		cmd.Env = append(cmd.Env, "GOEXPERIMENT=noloopvar", "HOME="+tmpdir)
		cmd.Dir = filepath.Join("testdata", "inlines")

		b, e := cmd.CombinedOutput()
		if e != nil {
			t.Error(e)
		}
		return string(b)
	}

	a := f(root + "/a")
	b := f(root + "/b")
	c := f(root + "/c")
	m := f(root)

	t.Logf(a)
	t.Logf(b)
	t.Logf(c)
	t.Logf(m)

	if !strings.Contains(a, "f, af, bf, abf, cf sums = 100, 45, 100, 100, 100") {
		t.Errorf("Did not see expected value of a")
	}
	if !strings.Contains(b, "f, af, bf, abf, cf sums = 100, 100, 45, 45, 100") {
		t.Errorf("Did not see expected value of b")
	}
	if !strings.Contains(c, "f, af, bf, abf, cf sums = 100, 100, 100, 100, 45") {
		t.Errorf("Did not see expected value of c")
	}
	if !strings.Contains(m, "f, af, bf, abf, cf sums = 45, 100, 100, 100, 100") {
		t.Errorf("Did not see expected value of m")
	}
}

func TestLoopVarHashes(t *testing.T) {
	switch runtime.GOOS {
	case "linux", "darwin":
	default:
		t.Skipf("Slow test, usually avoid it, os=%s not linux or darwin", runtime.GOOS)
	}
	switch runtime.GOARCH {
	case "amd64", "arm64":
	default:
		t.Skipf("Slow test, usually avoid it, arch=%s not amd64 or arm64", runtime.GOARCH)
	}

	testenv.MustHaveGoBuild(t)
	gocmd := testenv.GoToolPath(t)
	tmpdir := t.TempDir()

	root := "cmd/compile/internal/loopvar/testdata/inlines"

	f := func(hash string) string {
		// This disables the loopvar change, except for the specified package.
		// The effect should follow the package, even though everything (except "c")
		// is inlined.
		cmd := testenv.Command(t, gocmd, "run", root)
		cmd.Env = append(cmd.Env, "GOCOMPILEDEBUG=loopvarhash=FS"+hash, "HOME="+tmpdir)
		cmd.Dir = filepath.Join("testdata", "inlines")

		b, _ := cmd.CombinedOutput()
		// Ignore the error, sometimes it's supposed to fail, the output test will catch it.
		return string(b)
	}

	m := f("000100000010011111101100")
	t.Logf(m)

	mCount := strings.Count(m, "loopvarhash triggered POS=main.go:27:6")
	otherCount := strings.Count(m, "loopvarhash")
	if mCount < 1 {
		t.Errorf("Did not see expected value of m compile")
	}
	if mCount != otherCount {
		t.Errorf("Saw extraneous hash matches")
	}
	// This next test carefully dodges a bug-to-be-fixed with inlined locations for ir.Names.
	if !strings.Contains(m, ", 100, 100, 100, 100") {
		t.Errorf("Did not see expected value of m run")
	}
}

func TestLoopVarOpt(t *testing.T) {
	switch runtime.GOOS {
	case "linux", "darwin":
	default:
		t.Skipf("Slow test, usually avoid it, os=%s not linux or darwin", runtime.GOOS)
	}
	switch runtime.GOARCH {
	case "amd64", "arm64":
	default:
		t.Skipf("Slow test, usually avoid it, arch=%s not amd64 or arm64", runtime.GOARCH)
	}

	testenv.MustHaveGoBuild(t)
	gocmd := testenv.GoToolPath(t)

	cmd := testenv.Command(t, gocmd, "run", "-gcflags=-d=loopvar=2", "opt.go")
	cmd.Dir = filepath.Join("testdata")

	b, err := cmd.CombinedOutput()
	m := string(b)

	t.Logf(m)

	yCount := strings.Count(m, "opt.go:16:6: transformed loop variable private escapes (loop inlined into ./opt.go:30)")
	nCount := strings.Count(m, "shared")

	if yCount != 1 {
		t.Errorf("yCount=%d != 1", yCount)
	}
	if nCount > 0 {
		t.Errorf("nCount=%d > 0", nCount)
	}
	if err != nil {
		t.Errorf("err=%v != nil", err)
	}

}
