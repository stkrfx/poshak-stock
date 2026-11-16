//go:build windows

package service

import (
	"context"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"time"

	"github.com/stkrfx/stock-integrator/internal/syncer"
	"golang.org/x/sys/windows/svc"
	"golang.org/x/sys/windows/svc/eventlog"
	"golang.org/x/sys/windows/svc/mgr"
)

// windowsServiceHandler implements golang.org/x/sys/windows/svc.Handler.
type windowsServiceHandler struct {
	orchestrator *syncer.Orchestrator
}

func (h *windowsServiceHandler) Execute(
	args []string,
	r <-chan svc.ChangeRequest,
	changes chan<- svc.Status,
) (ssec bool, errno uint32) {
	const cmdsAccepted = svc.AcceptStop | svc.AcceptShutdown
	changes <- svc.Status{State: svc.StartPending}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	doneCh := make(chan error, 1)
	go func() {
		doneCh <- h.orchestrator.RunLoop(ctx)
	}()

	changes <- svc.Status{State: svc.Running, Accepts: cmdsAccepted}

loop:
	for {
		select {
		case err := <-doneCh:
			if err != nil {
				log.Printf("[ERROR] Orchestrator loop exited with error: %v", err)
				return false, 1
			}
			break loop

		case c := <-r:
			switch c.Cmd {
			case svc.Interrogate:
				changes <- c.CurrentStatus
			case svc.Stop, svc.Shutdown:
				log.Println("[INFO] Received Windows Service Stop/Shutdown signal...")
				changes <- svc.Status{State: svc.StopPending}
				cancel()
				select {
				case <-doneCh:
				case <-time.After(15 * time.Second):
				}
				break loop
			default:
				log.Printf("[WARN] Unexpected Windows Service control request #%d", c)
			}
		}
	}

	changes <- svc.Status{State: svc.Stopped}
	return false, 0
}

// IsWindowsServiceSession returns true if the process is currently running inside the Windows Service Control Manager.
func IsWindowsServiceSession() (bool, error) {
	return svc.IsWindowsService()
}

// RunAsWindowsService starts the orchestrator inside the Windows Service Control Manager.
func RunAsWindowsService(serviceName string, orchestrator *syncer.Orchestrator) error {
	elog, err := eventlog.Open(serviceName)
	if err == nil {
		defer elog.Close()
		_ = elog.Info(1, fmt.Sprintf("%s service starting", serviceName))
	}
	return svc.Run(serviceName, &windowsServiceHandler{orchestrator: orchestrator})
}

// InstallWindowsService registers the executable as an automatic-start Windows Service.
func InstallWindowsService(serviceName string, displayName string, description string) error {
	exePath, err := os.Executable()
	if err != nil {
		return err
	}
	exePath, err = filepath.Abs(exePath)
	if err != nil {
		return err
	}

	m, err := mgr.Connect()
	if err != nil {
		return fmt.Errorf("failed to connect to Windows Service Manager (run as Administrator): %w", err)
	}
	defer m.Disconnect()

	s, err := m.OpenService(serviceName)
	if err == nil {
		s.Close()
		return fmt.Errorf("service %s already exists", serviceName)
	}

	s, err = m.CreateService(
		serviceName,
		exePath,
		mgr.Config{
			DisplayName:      displayName,
			Description:      description,
			StartType:        mgr.StartAutomatic,
			ServiceStartName: "LocalSystem",
		},
	)
	if err != nil {
		return fmt.Errorf("failed to create Windows Service: %w", err)
	}
	defer s.Close()

	_ = eventlog.InstallAsEventCreate(serviceName, eventlog.Error|eventlog.Warning|eventlog.Info)
	return nil
}

// RemoveWindowsService uninstalls the Windows Service.
func RemoveWindowsService(serviceName string) error {
	m, err := mgr.Connect()
	if err != nil {
		return fmt.Errorf("failed to connect to Windows Service Manager (run as Administrator): %w", err)
	}
	defer m.Disconnect()

	s, err := m.OpenService(serviceName)
	if err != nil {
		return fmt.Errorf("service %s is not installed: %w", serviceName, err)
	}
	defer s.Close()

	_ = eventlog.Remove(serviceName)
	return s.Delete()
}
