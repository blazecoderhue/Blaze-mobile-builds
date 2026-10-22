package llm

import (
	"strings"
	"testing"
)

func TestParseStreamFinishReason(t *testing.T) {
	cut := "data: {\"choices\":[{\"delta\":{\"content\":\"Once upon\"}}]}\n" +
		"data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\ndata: [DONE]\n"
	r, err := ParseStream(strings.NewReader(cut), nil, nil)
	if err != nil || r.Finish != "length" || !r.Truncated() || r.Text != "Once upon" {
		t.Fatalf("length: %+v %v", r, err)
	}
	whole := "data: {\"choices\":[{\"delta\":{\"content\":\"Done.\"},\"finish_reason\":null}]}\n" +
		"data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\ndata: [DONE]\n"
	r, err = ParseStream(strings.NewReader(whole), nil, nil)
	if err != nil || r.Finish != "stop" || r.Truncated() {
		t.Fatalf("stop: %+v %v", r, err)
	}
	var nilRes *Result
	if nilRes.Truncated() {
		t.Fatal("nil result is not truncated")
	}
}
