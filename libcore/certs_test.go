package libcore

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"testing"
	"time"
)

// Generates a self-signed CA and a leaf certificate it issues, returning both
// as parsed certificates plus the CA in PEM form.
func makeTestCA(t *testing.T) (caPEM []byte, ca *x509.Certificate, leaf *x509.Certificate) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		t.Fatal(err)
	}
	caTemplate := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: "nb4a test ca"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(time.Hour),
		IsCA:                  true,
		KeyUsage:              x509.KeyUsageCertSign,
		BasicConstraintsValid: true,
	}
	caDER, err := x509.CreateCertificate(rand.Reader, caTemplate, caTemplate, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	ca, err = x509.ParseCertificate(caDER)
	if err != nil {
		t.Fatal(err)
	}
	leafSerial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		t.Fatal(err)
	}
	leafTemplate := &x509.Certificate{
		SerialNumber: leafSerial,
		Subject:      pkix.Name{CommonName: "nb4a test leaf"},
		DNSNames:     []string{"leaf.test"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	leafDER, err := x509.CreateCertificate(rand.Reader, leafTemplate, ca, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	leaf, err = x509.ParseCertificate(leafDER)
	if err != nil {
		t.Fatal(err)
	}
	caPEM = pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: caDER})
	return caPEM, ca, leaf
}

// updateRootCACerts swaps crypto/x509.systemRoots through a linkname; this
// verifies the injected CA is actually visible through x509's own reader
// (SystemCertPool returns a clone of the real systemRoots), so a broken
// linkname (e.g. after a Go upgrade) fails here instead of silently disabling
// the custom CA at runtime.
func TestUpdateRootCACertsInjectsCA(t *testing.T) {
	caPEM, _, leaf := makeTestCA(t)

	// Verifying against SystemCertPool() exercises the real systemRoots
	// variable on every platform. (On darwin Verify with nil Roots diverts to
	// the platform verifier and would not observe the injection at all.)
	verifyAgainstSystemRoots := func() error {
		roots, err := x509.SystemCertPool()
		if err != nil {
			return err
		}
		_, err = leaf.Verify(x509.VerifyOptions{Roots: roots, DNSName: "leaf.test"})
		return err
	}

	if err := verifyAgainstSystemRoots(); err == nil {
		t.Fatal("leaf trusted before CA injection")
	}

	systemRootsMu.Lock()
	oldRoots := systemRoots
	systemRootsMu.Unlock()
	t.Cleanup(func() {
		systemRootsMu.Lock()
		systemRoots = oldRoots
		systemRootsMu.Unlock()
	})

	updateRootCACerts(caPEM)
	if err := verifyAgainstSystemRoots(); err != nil {
		t.Fatal("leaf not trusted after CA injection:", err)
	}
}
