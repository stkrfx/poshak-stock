//go:build !windows

package service

import (
	"errors"

	"github.com/stkrfx/stock-integrator/internal/syncer"
)

func IsWindowsServiceSession() (bool, error) {
	return false, nil
}

func RunAsWindowsService(serviceName string, orchestrator *syncer.Orchestrator) error {
	return errors.New("Windows Service mode is only supported on Windows OS")
}

func InstallWindowsService(serviceName string, displayName string, description string) error {
	return errors.New("Windows Service installation is only supported on Windows OS")
}

func RemoveWindowsService(serviceName string) error {
	return errors.New("Windows Service removal is only supported on Windows OS")
}
