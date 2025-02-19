package org.bouncycastle.openpgp.operator.jcajce;

import java.io.OutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Provider;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;

import org.bouncycastle.bcpg.AEADEncDataPacket;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.jcajce.io.CipherOutputStream;
import org.bouncycastle.jcajce.util.DefaultJcaJceHelper;
import org.bouncycastle.jcajce.util.NamedJcaJceHelper;
import org.bouncycastle.jcajce.util.ProviderJcaJceHelper;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.operator.PGPAEADDataEncryptor;
import org.bouncycastle.openpgp.operator.PGPDataEncryptor;
import org.bouncycastle.openpgp.operator.PGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.util.Arrays;

/**
 * {@link PGPDataEncryptorBuilder} implementation that sources cryptographic primitives using the
 * JCE APIs.
 * <p>
 * By default, cryptographic primitives will be loaded using the default JCE load order (i.e.
 * without specifying a provider).
 * A specific provider can be specified using one of the {@link #setProvider(String)} methods.
 * </p>
 */
public class JcePGPDataEncryptorBuilder
    implements PGPDataEncryptorBuilder
{
    private OperatorHelper helper = new OperatorHelper(new DefaultJcaJceHelper());
    private SecureRandom random;
    private boolean withIntegrityPacket;
    private int encAlgorithm;
    private int aeadAlgorithm = -1;
    private int chunkSize;

    /**
     * Constructs a new data encryptor builder for a specified cipher type.
     *
     * @param encAlgorithm one of the {@link SymmetricKeyAlgorithmTags supported symmetric cipher
     *                     algorithms}. May not be {@link SymmetricKeyAlgorithmTags#NULL}.
     */
    public JcePGPDataEncryptorBuilder(int encAlgorithm)
    {
        this.encAlgorithm = encAlgorithm;

        if (encAlgorithm == 0)
        {
            throw new IllegalArgumentException("null cipher specified");
        }
    }

    /**
     * Sets whether or not the resulting encrypted data will be protected using an integrity packet.
     *
     * @param withIntegrityPacket true if an integrity packet is to be included, false otherwise.
     * @return the current builder.
     */
    public JcePGPDataEncryptorBuilder setWithIntegrityPacket(boolean withIntegrityPacket)
    {
        this.withIntegrityPacket = withIntegrityPacket;

        return this;
    }

    public JcePGPDataEncryptorBuilder setWithAEAD(int aeadAlgorithm, int chunkSize)
    {
        if (encAlgorithm != SymmetricKeyAlgorithmTags.AES_128
            && encAlgorithm != SymmetricKeyAlgorithmTags.AES_192
            && encAlgorithm != SymmetricKeyAlgorithmTags.AES_256)
        {
            throw new IllegalStateException("AEAD algorithms can only be used with AES");
        }

        if (chunkSize < 6)
        {
            throw new IllegalArgumentException("minimum chunkSize is 6");
        }

        this.aeadAlgorithm = aeadAlgorithm;
        this.chunkSize = chunkSize - 6;

        return this;
    }

    /**
     * Sets the JCE provider to source cryptographic primitives from.
     *
     * @param provider the JCE provider to use.
     * @return the current builder.
     */
    public JcePGPDataEncryptorBuilder setProvider(Provider provider)
    {
        this.helper = new OperatorHelper(new ProviderJcaJceHelper(provider));

        return this;
    }

    /**
     * Sets the JCE provider to source cryptographic primitives from.
     *
     * @param providerName the name of the JCE provider to use.
     * @return the current builder.
     */
    public JcePGPDataEncryptorBuilder setProvider(String providerName)
    {
        this.helper = new OperatorHelper(new NamedJcaJceHelper(providerName));

        return this;
    }

    /**
     * Provide a user defined source of randomness.
     * <p>
     * If no SecureRandom is configured, a default SecureRandom will be used.
     * </p>
     *
     * @param random the secure random to be used.
     * @return the current builder.
     */
    public JcePGPDataEncryptorBuilder setSecureRandom(SecureRandom random)
    {
        this.random = random;

        return this;
    }

    public int getAlgorithm()
    {
        return encAlgorithm;
    }

    public SecureRandom getSecureRandom()
    {
        if (random == null)
        {
            random = new SecureRandom();
        }

        return random;
    }

    public PGPDataEncryptor build(byte[] keyBytes)
        throws PGPException
    {
        if (aeadAlgorithm > 0)
        {
            return new MyAeadDataEncryptor(keyBytes);
        }
        return new MyPGPDataEncryptor(keyBytes);
    }

    private class MyPGPDataEncryptor
        implements PGPDataEncryptor
    {
        private final Cipher c;

        MyPGPDataEncryptor(byte[] keyBytes)
            throws PGPException
        {
            c = helper.createStreamCipher(encAlgorithm, withIntegrityPacket);

            try
            {
                if (withIntegrityPacket)
                {
                    byte[] iv = new byte[c.getBlockSize()];

                    c.init(Cipher.ENCRYPT_MODE, JcaJcePGPUtil.makeSymmetricKey(encAlgorithm, keyBytes), new IvParameterSpec(iv));
                }
                else
                {
                    c.init(Cipher.ENCRYPT_MODE, JcaJcePGPUtil.makeSymmetricKey(encAlgorithm, keyBytes));
                }
            }
            catch (InvalidKeyException e)
            {
                throw new PGPException("invalid key: " + e.getMessage(), e);
            }
            catch (InvalidAlgorithmParameterException e)
            {
                throw new PGPException("imvalid algorithm parameter: " + e.getMessage(), e);
            }
        }

        public OutputStream getOutputStream(OutputStream out)
        {
            return new CipherOutputStream(out, c);
        }

        public PGPDigestCalculator getIntegrityCalculator()
        {
            if (withIntegrityPacket)
            {
                return new SHA1PGPDigestCalculator();
            }

            return null;
        }

        public int getBlockSize()
        {
            return c.getBlockSize();
        }
    }

    private class MyAeadDataEncryptor
        implements PGPAEADDataEncryptor
    {
        private final Cipher c;
        private final byte[] keyBytes;
        private final byte[] iv;

        MyAeadDataEncryptor(byte[] keyBytes)
            throws PGPException
        {
            this.keyBytes = keyBytes;
            this.c = helper.createAEADCipher(encAlgorithm, aeadAlgorithm);
            this.iv = new byte[AEADEncDataPacket.getIVLength((byte)aeadAlgorithm)];

            getSecureRandom().nextBytes(iv);
        }

        public OutputStream getOutputStream(OutputStream out)
        {
            try
            {
                return new OperatorHelper.PGPAeadOutputStream(out, c, JcaJcePGPUtil.makeSymmetricKey(encAlgorithm, keyBytes), encAlgorithm, aeadAlgorithm, chunkSize, iv);
            }
            catch (Exception e)
            {
                throw new IllegalStateException("unable to process stream: " + e.getMessage());
            }
        }

        public PGPDigestCalculator getIntegrityCalculator()
        {
            return null;
        }

        public int getBlockSize()
        {
            return c.getBlockSize();
        }

        public int getAEADAlgorithm()
        {
            return aeadAlgorithm;
        }

        public int getChunkSize()
        {
            return chunkSize;
        }

        public byte[] getIV()
        {
            return Arrays.clone(iv);
        }
    }
}
