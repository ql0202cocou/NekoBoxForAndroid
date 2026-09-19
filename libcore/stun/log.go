// Copyright 2016 Cong Ding
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package stun

import (
	"log"
	"os"
	"sync/atomic"
)

// Logger is a simple logger specified for this STUN client.
type Logger struct {
	// logger is a named field instead of an embedded one: embedding
	// log.Logger by value copies its mutex, and embedding it by pointer
	// would still promote the Fatal* (os.Exit) methods into the API of
	// this package.
	logger *log.Logger
	debug  atomic.Bool
	info   atomic.Bool
}

// NewLogger creates a default logger.
// Note: the output goes to stdout, which is not visible in Android logcat,
// so this logger is only useful for desktop debugging of this vendored
// package; it is kept dependency-free instead of being wired to the app's
// logger.
func NewLogger() *Logger {
	return &Logger{logger: log.New(os.Stdout, "", log.LstdFlags)}
}

// Debugln outputs the log in the format of log.Println.
func (l *Logger) Debugln(v ...interface{}) {
	if l.debug.Load() {
		l.logger.Println(v...)
	}
}

// Info outputs the log in the format of log.Print.
func (l *Logger) Info(v ...interface{}) {
	if l.info.Load() {
		l.logger.Print(v...)
	}
}
