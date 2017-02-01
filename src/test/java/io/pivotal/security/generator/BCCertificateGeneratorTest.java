package io.pivotal.security.generator;

import com.greghaskins.spectrum.Spectrum;
import io.pivotal.security.CredentialManagerApp;
import io.pivotal.security.controller.v1.CertificateSecretParameters;
import io.pivotal.security.data.CertificateAuthorityService;
import io.pivotal.security.secret.Certificate;
import io.pivotal.security.util.CertificateFormatter;
import io.pivotal.security.util.CurrentTimeProvider;
import io.pivotal.security.util.DatabaseProfileResolver;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Calendar;
import java.util.Date;

import static com.greghaskins.spectrum.Spectrum.afterEach;
import static com.greghaskins.spectrum.Spectrum.beforeEach;
import static com.greghaskins.spectrum.Spectrum.describe;
import static com.greghaskins.spectrum.Spectrum.it;
import static io.pivotal.security.helper.SpectrumHelper.wireAndUnwire;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(Spectrum.class)
@ActiveProfiles(value = "unit-test", resolver = DatabaseProfileResolver.class)
@SpringBootTest(classes = CredentialManagerApp.class)
public class BCCertificateGeneratorTest {
  private BCCertificateGenerator subject;

  private LibcryptoRsaKeyPairGenerator keyGenerator;
  private SignedCertificateGenerator signedCertificateGenerator;
  private CertificateAuthorityService certificateAuthorityService;
  private BouncyCastleProvider bouncyCastleProvider;

  @MockBean
  private CurrentTimeProvider currentTimeProvider;

  @Autowired
  FakeKeyPairGenerator fakeKeyPairGenerator;

  @MockBean
  RandomSerialNumberGenerator randomSerialNumberGenerator;

  private KeyPair childCertificateKeyPair;
  private X500Name caDn;
  private KeyPair caKeyPair;
  private Certificate certificateAuthority;
  private CertificateSecretParameters inputParameters;
  private X509CertificateHolder childCertificateHolder;
  private X509Certificate caX509Cert;
  private X509Certificate childCertificate;
  private String privateKey;

  {
    wireAndUnwire(this, false);

    beforeEach(() -> {
      keyGenerator = mock(LibcryptoRsaKeyPairGenerator.class);
      signedCertificateGenerator = mock(SignedCertificateGenerator.class);
      certificateAuthorityService = mock(CertificateAuthorityService.class);
      bouncyCastleProvider = new BouncyCastleProvider();

      subject = new BCCertificateGenerator(keyGenerator, signedCertificateGenerator, certificateAuthorityService, bouncyCastleProvider);

      Security.addProvider(bouncyCastleProvider);

      when(currentTimeProvider.getNow()).thenReturn(new Calendar.Builder().setInstant(22233333L).build());
      when(randomSerialNumberGenerator.generate()).thenReturn(BigInteger.TEN);

      caDn = new X500Name("O=foo,ST=bar,C=mars");
      caKeyPair = fakeKeyPairGenerator.generate();
      X509CertificateHolder caX509CertHolder = generateX509CertificateAuthority();
      caX509Cert = new JcaX509CertificateConverter().setProvider("BC").getCertificate(caX509CertHolder);
      privateKey = CertificateFormatter.pemOf(caKeyPair.getPrivate());
      certificateAuthority = new Certificate(null, CertificateFormatter.pemOf(caX509Cert), privateKey);

      inputParameters = new CertificateSecretParameters()
        .setOrganization("foo")
        .setState("bar")
        .setCaName("my-ca-name")
        .setCountry("mars")
        .setDurationDays(365);
    });

    afterEach(() -> Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME));

    describe("when CA exists", () -> {
      beforeEach(() -> {
        childCertificateKeyPair = fakeKeyPairGenerator.generate();
        when(keyGenerator.generateKeyPair(anyInt())).thenReturn(childCertificateKeyPair);
        when(certificateAuthorityService.findMostRecent("my-ca-name")).thenReturn(certificateAuthority);
        childCertificateHolder = generateChildCertificateSignedByCa(
            childCertificateKeyPair, caKeyPair.getPrivate(), caDn);
        childCertificate = new JcaX509CertificateConverter()
            .setProvider("BC").getCertificate(childCertificateHolder);
        when(signedCertificateGenerator.getSignedByIssuer(caDn, caKeyPair.getPrivate(),
            childCertificateKeyPair, inputParameters)).thenReturn(childCertificate);
      });

      it("generates a valid childCertificate", () -> {
        Certificate certificateSecret = subject.generateSecret(inputParameters);

        assertThat(certificateSecret.getCaCertificate(),
            equalTo(certificateAuthority.getPublicKeyCertificate()));
        assertThat(certificateSecret.getPrivateKey(),
            equalTo(CertificateFormatter.pemOf(childCertificateKeyPair.getPrivate())));
        assertThat(certificateSecret.getPublicKeyCertificate(),
            equalTo(CertificateFormatter.pemOf(childCertificate)));
        verify(keyGenerator, times(1)).generateKeyPair(2048);
      });

      describe("when a key length is given", () -> {
        beforeEach(() -> inputParameters.setKeyLength(4096));

        it("generates a valid childCertificate", () -> {
          Certificate certificateSecret = subject.generateSecret(inputParameters);

          assertThat(certificateSecret, notNullValue());
          verify(keyGenerator, times(1)).generateKeyPair(4096);
        });
      });
    });

    describe("when the selfSign flag is set", () -> {
      final X509Certificate[] certificate = new X509Certificate[1];

      beforeEach(() -> {
        inputParameters.setCaName(null);
        inputParameters.setSelfSign(true);
        X509CertificateHolder certHolder = generateX509SelfSignedCert();
        certificate[0] = new JcaX509CertificateConverter()
          .setProvider("BC").getCertificate(certHolder);
        when(keyGenerator.generateKeyPair(anyInt())).thenReturn(caKeyPair);
        when(signedCertificateGenerator.getSelfSigned(caKeyPair, inputParameters)).thenReturn(certificate[0]);
      });

      it("generates a valid self-signed certificate", () -> {
        Certificate certificateSecret = subject.generateSecret(inputParameters);
        assertThat(certificateSecret.getPrivateKey(),
                equalTo(CertificateFormatter.pemOf(caKeyPair.getPrivate())));
        assertThat(certificateSecret.getPublicKeyCertificate(),
                equalTo(CertificateFormatter.pemOf(certificate[0])));
        assertThat(certificateSecret.getCaCertificate(), nullValue());
        verify(signedCertificateGenerator, times(1)).getSelfSigned(caKeyPair, inputParameters);
      });
    });
  }

  private X509CertificateHolder generateX509CertificateAuthority() throws Exception {
    return makeCert(caKeyPair, caKeyPair.getPrivate(), caDn, caDn, true);
  }

  private X509CertificateHolder generateX509SelfSignedCert() throws Exception {
    return makeCert(caKeyPair, caKeyPair.getPrivate(), caDn, caDn, false);
  }

  private X509CertificateHolder generateChildCertificateSignedByCa(KeyPair certKeyPair,
                                                                   PrivateKey caPrivateKey,
                                                                   X500Name caDn) throws Exception {
    return makeCert(certKeyPair, caPrivateKey, caDn, inputParameters.getDN(), false);
  }

  private X509CertificateHolder makeCert(KeyPair certKeyPair, PrivateKey caPrivateKey,
                                         X500Name caDn, X500Name subjectDN, boolean isCA) throws OperatorCreationException, NoSuchAlgorithmException, CertIOException {
    SubjectPublicKeyInfo publicKeyInfo = SubjectPublicKeyInfo.getInstance(certKeyPair.getPublic()
        .getEncoded());
    ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC")
        .build(caPrivateKey);

    Instant now = currentTimeProvider.getNow().toInstant();

    X509v3CertificateBuilder x509v3CertificateBuilder = new X509v3CertificateBuilder(
      caDn,
      BigInteger.TEN,
      Date.from(now),
      Date.from(now.plus(Duration.ofDays(365))),
      subjectDN,
      publicKeyInfo
    );
    x509v3CertificateBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(isCA));
    return x509v3CertificateBuilder.build(contentSigner);
  }
}
