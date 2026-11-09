package main

// Adapted from ViRb3/wgcf, MIT licence, commit 803310a.
import (
    "context"
    "crypto/rand"
    "crypto/tls"
    "fmt"
    "io"
    "net"
    utls "github.com/refraction-networking/utls"
)

var warpSessionCache = utls.NewLRUClientSessionCache(64)
func handshakeWarpTLS(ctx context.Context, rawConn net.Conn, serverName string, config *tls.Config) (*utls.UConn, error) {
	if config.ServerName != "" {
		serverName = config.ServerName
	}
	uConfig := &utls.Config{
		ServerName:            serverName,
		RootCAs:               config.RootCAs,
		InsecureSkipVerify:    config.InsecureSkipVerify,
		VerifyPeerCertificate: config.VerifyPeerCertificate,
		ClientSessionCache:    warpSessionCache,
		MinVersion:            utls.VersionTLS12,
		MaxVersion:            utls.VersionTLS12,
		NextProtos:            []string{"http/1.1"},
		KeyLogWriter:          config.KeyLogWriter,
	}
	uConn := utls.UClient(rawConn, uConfig, utls.HelloCustom)
	if err := uConn.ApplyPreset(warpClientHelloSpec()); err != nil {
		return nil, fmt.Errorf("apply WARP TLS fingerprint: %w", err)
	}

	// Conscrypt leaves the session ID empty on a fresh TLS 1.2 connection.
	// uTLS normally generates one for every custom ClientHello, so clear it
	// before session lookup and restore it only when a cached ticket is used.
	uConn.HandshakeState.Hello.SessionId = nil
	if err := uConn.BuildHandshakeState(); err != nil {
		return nil, fmt.Errorf("build WARP TLS ClientHello: %w", err)
	}
	if warpSessionTicketPresent(uConn) {
		sessionID := make([]byte, 32)
		if _, err := io.ReadFull(rand.Reader, sessionID); err != nil {
			return nil, fmt.Errorf("generate TLS session ID: %w", err)
		}
		uConn.HandshakeState.Hello.SessionId = sessionID
		if err := uConn.MarshalClientHello(); err != nil {
			return nil, fmt.Errorf("marshal resumed WARP TLS ClientHello: %w", err)
		}
	}

	if err := uConn.HandshakeContext(ctx); err != nil {
		return nil, fmt.Errorf("WARP TLS handshake: %w", err)
	}
	return uConn, nil
}

func warpSessionTicketPresent(conn *utls.UConn) bool {
	for _, extension := range conn.Extensions {
		if ticket, ok := extension.(*utls.SessionTicketExtension); ok {
			return len(ticket.Ticket) != 0
		}
	}
	return false
}

func warpClientHelloSpec() *utls.ClientHelloSpec {
	return &utls.ClientHelloSpec{
		TLSVersMin: utls.VersionTLS12,
		TLSVersMax: utls.VersionTLS12,
		CipherSuites: []uint16{
			utls.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
			utls.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
		},
		CompressionMethods: []uint8{0},
		Extensions: []utls.TLSExtension{
			&utls.SNIExtension{},
			&utls.ExtendedMasterSecretExtension{},
			&utls.RenegotiationInfoExtension{Renegotiation: utls.RenegotiateNever},
			&utls.SupportedCurvesExtension{Curves: []utls.CurveID{
				utls.X25519,
				utls.CurveP256,
				utls.CurveP384,
			}},
			&utls.SupportedPointsExtension{SupportedPoints: []uint8{0}},
			&utls.SessionTicketExtension{},
			&utls.ALPNExtension{AlpnProtocols: []string{"http/1.1"}},
			&utls.StatusRequestExtension{},
			&utls.SignatureAlgorithmsExtension{SupportedSignatureAlgorithms: []utls.SignatureScheme{
				utls.ECDSAWithP256AndSHA256,
				utls.PSSWithSHA256,
				utls.PKCS1WithSHA256,
				utls.ECDSAWithP384AndSHA384,
				utls.PSSWithSHA384,
				utls.PKCS1WithSHA384,
				utls.PSSWithSHA512,
				utls.PKCS1WithSHA512,
				utls.PKCS1WithSHA1,
			}},
			&utls.UtlsPaddingExtension{GetPaddingLen: utls.BoringPaddingStyle},
		},
	}
}
