package llm

import (
	"sync/atomic"
	"testing"
	"time"
)

func TestRunBatchParallelReadsKeepOrder(t *testing.T) {
	names := []string{"read", "read", "read", "write", "read", "read"}
	var live, peak int32
	var order []int
	ok := RunBatch(len(names),
		func(i int) bool { return names[i] == "read" },
		func(i int) bool { return true },
		func(i int) string {
			n := atomic.AddInt32(&live, 1)
			for {
				p := atomic.LoadInt32(&peak)
				if n <= p || atomic.CompareAndSwapInt32(&peak, p, n) {
					break
				}
			}
			time.Sleep(time.Duration(30-i*4) * time.Millisecond) // later calls finish first
			atomic.AddInt32(&live, -1)
			return names[i]
		},
		func(i int, res string) {
			if res != names[i] {
				t.Fatalf("call %d got %q", i, res)
			}
			order = append(order, i)
		})
	if !ok {
		t.Fatal("batch should finish")
	}
	for i, v := range order {
		if v != i {
			t.Fatalf("finish order %v, want 0..5", order)
		}
	}
	if peak < 3 {
		t.Fatalf("the three leading reads should overlap, peak was %d", peak)
	}
}

func TestRunBatchWritesAreSequentialAndStopHonoured(t *testing.T) {
	var live, peak int32
	ran := 0
	ok := RunBatch(4,
		func(int) bool { return false },
		func(i int) bool { return i < 2 },
		func(int) string {
			if n := atomic.AddInt32(&live, 1); n > peak {
				peak = n
			}
			time.Sleep(5 * time.Millisecond)
			atomic.AddInt32(&live, -1)
			ran++
			return ""
		},
		func(int, string) {})
	if ok || ran != 2 || peak != 1 {
		t.Fatalf("ok=%v ran=%d peak=%d, want stop after 2 sequential calls", ok, ran, peak)
	}
}
