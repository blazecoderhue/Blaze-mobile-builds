package llm

import "sync"

// MaxParallel caps how many read-only tool calls run at the same time.
const MaxParallel = 6

// RunBatch runs a model's tool calls 0..n-1 in order, except that a run of consecutive
// calls safe(i) allows (reads: file reads, searches, fetches) runs at the same time, up
// to MaxParallel at once. start and finish are always called in call order on the
// caller's goroutine, so history and the UI see the calls exactly as the model made
// them; only run happens concurrently. start returning false stops the batch (Stop was
// pressed): calls already started still finish. It reports whether every call ran.
func RunBatch(n int, safe func(i int) bool, start func(i int) bool, run func(i int) string, finish func(i int, res string)) bool {
	for i := 0; i < n; {
		j := i + 1
		if safe(i) {
			for j < n && j-i < MaxParallel && safe(j) {
				j++
			}
		}
		if j-i == 1 {
			if !start(i) {
				return false
			}
			finish(i, run(i))
			i++
			continue
		}
		res := make([]string, j-i)
		var wg sync.WaitGroup
		started := i
		for k := i; k < j; k++ {
			if !start(k) {
				break
			}
			started = k + 1
			wg.Add(1)
			go func(k int) {
				defer wg.Done()
				res[k-i] = run(k)
			}(k)
		}
		wg.Wait()
		for k := i; k < started; k++ {
			finish(k, res[k-i])
		}
		if started < j {
			return false
		}
		i = j
	}
	return true
}
