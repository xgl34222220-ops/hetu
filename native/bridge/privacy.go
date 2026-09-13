package main

import (
 "io"
 "github.com/metacubex/mihomo/log"
 logrus "github.com/sirupsen/logrus"
)
// Native library initialization happens before configuration parsing. Diagnostic
// errors crossing JNI are deliberately generic; raw provider URLs are not logs.
func init() { log.SetLevel(log.SILENT); logrus.SetOutput(io.Discard) }
