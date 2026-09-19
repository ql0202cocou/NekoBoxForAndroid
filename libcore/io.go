package libcore

import (
	"archive/zip"
	"io"
	"libcore/device"
	"os"
	"path/filepath"
	"strings"

	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/ulikunitz/xz"
)

func Unxz(archive string, path string) (err error) {
	defer device.DeferPanicToError("Unxz", func(err_ error) { err = err_ })

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
	_, err = io.Copy(o, r)
	cerr := o.Close()
	if err != nil {
		return err
	}
	return cerr
}

func Unzip(archive string, path string) (err error) {
	defer device.DeferPanicToError("Unzip", func(err_ error) { err = err_ })

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
			return E.New("zip entry ", file.Name, " escapes destination ", path)
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

		var errs error
		_, err = io.Copy(newFile, zipFile)
		errs = E.Errors(errs, err)
		errs = E.Errors(errs, common.Close(zipFile, newFile))
		if errs != nil {
			return errs
		}
	}

	return nil
}
