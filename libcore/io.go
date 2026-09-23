package libcore

import (
	"archive/zip"
	"errors"
	"fmt"
	"libcore/device"
	"os"
	"path/filepath"
	"strings"

	"github.com/ulikunitz/xz"
)

func unxz(archive string, path string) (err error) {
	defer device.DeferPanicToError("unxz", func(err_ error) { err = err_ })

	i, err := os.Open(archive)
	if err != nil {
		return err
	}
	defer i.Close()
	r, err := xz.NewReader(i)
	if err != nil {
		return err
	}
	o, err := os.Create(path)
	if err != nil {
		return err
	}
	return copyAndClose(o, r)
}

func unzip(archive string, path string) (err error) {
	defer device.DeferPanicToError("unzip", func(err_ error) { err = err_ })

	r, err := zip.OpenReader(archive)
	if err != nil {
		return err
	}
	defer r.Close()

	err = os.MkdirAll(path, os.ModePerm)
	if err != nil {
		return err
	}

	for _, file := range r.File {
		filePath := filepath.Join(path, file.Name)
		if !strings.HasPrefix(filePath, filepath.Clean(path)+string(os.PathSeparator)) {
			return fmt.Errorf("zip entry %s escapes destination %s", file.Name, path)
		}

		if file.FileInfo().IsDir() {
			err = os.MkdirAll(filePath, os.ModePerm)
			if err != nil {
				return err
			}
			continue
		}

		// Zip entries are not guaranteed to be ordered: a file may appear
		// before the directory entry of its parent.
		err = os.MkdirAll(filepath.Dir(filePath), os.ModePerm)
		if err != nil {
			return err
		}

		newFile, err := os.Create(filePath)
		if err != nil {
			return err
		}

		zipFile, err := file.Open()
		if err != nil {
			newFile.Close()
			return err
		}

		if err := errors.Join(copyAndClose(newFile, zipFile), zipFile.Close()); err != nil {
			return err
		}
	}

	return nil
}
