package boxapi

import (
	"context"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
)

type SbV2rayServer struct {
	ss *SbStatsService
}

func NewSbV2rayServer(options option.V2RayStatsServiceOptions) *SbV2rayServer {
	return &SbV2rayServer{
		ss: NewSbStatsService(options),
	}
}

func (s *SbV2rayServer) Start() error { return nil }
func (s *SbV2rayServer) Close() error { return nil }

// NewSbStatsService 在 !Enabled 时返回 nil；把 nil 的 *SbStatsService 装箱成
// interface 是 typed-nil（接口本身非 nil），AppendTracker 后首次路由即
// panic，这里显式返回 nil interface
func (s *SbV2rayServer) StatsService() adapter.ConnectionTracker {
	if s.ss == nil {
		return nil
	}
	return s.ss
}

// NekoRay style API

func (s *SbV2rayServer) QueryStats(name string) int64 {
	value, err := s.ss.GetStats(context.TODO(), name, true)
	if err == nil {
		return value
	}
	return 0
}
