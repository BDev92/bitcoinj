/**
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.bitcoin.wallet;

import com.google.bitcoin.core.BloomFilter;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.crypto.*;
import com.google.bitcoin.store.UnreadableWalletException;
import com.google.bitcoin.utils.ListenerRegistration;
import com.google.bitcoin.utils.Threading;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import org.bitcoinj.wallet.Protos;
import org.spongycastle.crypto.params.KeyParameter;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.*;

/**
 * A {@link KeyChain} that implements the simplest model possible: it can have keys imported into it, and just acts as
 * a dumb bag of keys. It will, left to its own devices, always return the same key for usage by the wallet, although
 * it will automatically add one to itself if it's empty or if encryption is requested.
 */
public class BasicKeyChain implements EncryptableKeyChain {
    private final ReentrantLock lock = Threading.lock("BasicKeyChain");

    // Maps used to let us quickly look up a key given data we find in transcations or the block chain.
    private final LinkedHashMap<ByteString, ECKey> hashToKeys;
    private final LinkedHashMap<ByteString, ECKey> pubkeyToKeys;
    @Nullable private final KeyCrypter keyCrypter;

    private final CopyOnWriteArrayList<ListenerRegistration<KeyChainEventListener>> listeners;

    public BasicKeyChain() {
        this(null);
    }

    BasicKeyChain(@Nullable KeyCrypter crypter) {
        this.keyCrypter = crypter;
        hashToKeys = new LinkedHashMap<ByteString, ECKey>();
        pubkeyToKeys = new LinkedHashMap<ByteString, ECKey>();
        listeners = new CopyOnWriteArrayList<ListenerRegistration<KeyChainEventListener>>();
    }

    /** Returns the {@link KeyCrypter} in use or null if the key chain is not encrypted. */
    @Nullable
    public KeyCrypter getKeyCrypter() {
        lock.lock();
        try {
            return keyCrypter;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ECKey getKey(KeyPurpose purpose) {
        lock.lock();
        try {
            if (hashToKeys.isEmpty()) {
                checkState(keyCrypter == null);   // We will refuse to encrypt an empty key chain.
                final ECKey key = new ECKey();
                importKeyLocked(key);
                queueOnKeysAdded(ImmutableList.of(key));
            }
            return hashToKeys.values().iterator().next();
        } finally {
            lock.unlock();
        }
    }

    /** Returns a copy of the list of keys that this chain is managing. */
    public List<ECKey> getKeys() {
        lock.lock();
        try {
            return new ArrayList<ECKey>(hashToKeys.values());
        } finally {
            lock.unlock();
        }
    }

    public int importKeys(ECKey... keys) {
        return importKeys(ImmutableList.copyOf(keys));
    }

    public int importKeys(List<? extends ECKey> keys) {
        lock.lock();
        try {
            // Check that if we're encrypted, the keys are all encrypted, and if we're not, that none are.
            // We are NOT checking that the parameters or actual password match here: if you screw up and
            // import keys with mismatched crypters or keys/passwords, you lose!
            for (ECKey key : keys) {
                checkKeyEncryptionStateMatches(key);
            }
            List<ECKey> actuallyAdded = new ArrayList<ECKey>(keys.size());
            for (final ECKey key : keys) {
                if (hasKey(key)) continue;
                actuallyAdded.add(key);
                importKeyLocked(key);
            }
            if (actuallyAdded.size() > 0)
                queueOnKeysAdded(actuallyAdded);
            return actuallyAdded.size();
        } finally {
            lock.unlock();
        }
    }

    private void checkKeyEncryptionStateMatches(ECKey key) {
        if (keyCrypter == null && key.isEncrypted())
            throw new KeyCrypterException("Key is encrypted but chain is not");
        else if (keyCrypter != null && !key.isEncrypted())
            throw new KeyCrypterException("Key is not encrypted but chain is");
    }

    private void importKeyLocked(ECKey key) {
        pubkeyToKeys.put(ByteString.copyFrom(key.getPubKey()), key);
        hashToKeys.put(ByteString.copyFrom(key.getPubKeyHash()), key);
    }

    /* package */ void importKey(ECKey key) {
        lock.lock();
        try {
            importKeyLocked(key);
            queueOnKeysAdded(ImmutableList.of(key));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ECKey findKeyFromPubHash(byte[] pubkeyHash) {
        lock.lock();
        try {
            return hashToKeys.get(ByteString.copyFrom(pubkeyHash));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ECKey findKeyFromPubKey(byte[] pubkey) {
        lock.lock();
        try {
            return pubkeyToKeys.get(ByteString.copyFrom(pubkey));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean hasKey(ECKey key) {
        return findKeyFromPubKey(key.getPubKey()) != null;
    }

    @Override
    public int numKeys() {
        return pubkeyToKeys.size();
    }

    /**
     * Removes the given key from the keychain. Be very careful with this - losing a private key <b>destroys the
     * money associated with it</b>.
     * @return Whether the key was removed or not.
     */
    public boolean removeKey(ECKey key) {
        lock.lock();
        try {
            boolean a = hashToKeys.remove(ByteString.copyFrom(key.getPubKeyHash())) != null;
            boolean b = pubkeyToKeys.remove(ByteString.copyFrom(key.getPubKey())) != null;
            checkState(a == b);   // Should be in both maps or neither.
            return a;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long getEarliestKeyCreationTime() {
        lock.lock();
        try {
            long time = Long.MAX_VALUE;
            for (ECKey key : hashToKeys.values())
                time = Math.min(key.getCreationTimeSeconds(), time);
            return time;
        } finally {
            lock.unlock();
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // Serialization support
    //
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    Map<ECKey, Protos.Key.Builder> serializeToEditableProtobufs() {
        Map<ECKey, Protos.Key.Builder> result = new LinkedHashMap<ECKey, Protos.Key.Builder>();
        for (ECKey ecKey : hashToKeys.values()) {
            Protos.Key.Builder protoKey = serializeEncryptableItem(ecKey);
            protoKey.setPublicKey(ByteString.copyFrom(ecKey.getPubKey()));
            result.put(ecKey, protoKey);
        }
        return result;
    }

    @Override
    public List<Protos.Key> serializeToProtobuf() {
        Collection<Protos.Key.Builder> builders = serializeToEditableProtobufs().values();
        List<Protos.Key> result = new ArrayList<Protos.Key>(builders.size());
        for (Protos.Key.Builder builder : builders) result.add(builder.build());
        return result;
    }

    /*package*/ static Protos.Key.Builder serializeEncryptableItem(EncryptableItem item) {
        Protos.Key.Builder proto = Protos.Key.newBuilder();
        proto.setCreationTimestamp(item.getCreationTimeSeconds() * 1000);
        if (item.isEncrypted() && item.getEncryptedData() != null) {
            // The encrypted data can be missing for an "encrypted" key in the case of a deterministic wallet for
            // which the leaf keys chain to an encrypted parent and rederive their private keys on the fly. In that
            // case the caller in DeterministicKeyChain will take care of setting the type.
            EncryptedData data = item.getEncryptedData();
            proto.getEncryptedDataBuilder()
                    .setEncryptedPrivateKey(ByteString.copyFrom(data.encryptedBytes))
                    .setInitialisationVector(ByteString.copyFrom(data.initialisationVector));
            // We don't allow mixing of encryption types at the moment.
            checkState(item.getEncryptionType() == Protos.Wallet.EncryptionType.ENCRYPTED_SCRYPT_AES);
            proto.setType(Protos.Key.Type.ENCRYPTED_SCRYPT_AES);
        } else {
            final byte[] secret = item.getSecretBytes();
            // The secret might be missing in the case of a watching wallet, or a key for which the private key
            // is expected to be rederived on the fly from its parent.
            if (secret != null)
                proto.setSecretBytes(ByteString.copyFrom(secret));
            proto.setType(Protos.Key.Type.ORIGINAL);
        }
        return proto;
    }

    /**
     * Returns a new BasicKeyChain that contains all basic, ORIGINAL type keys extracted from the list. Unrecognised
     * key types are ignored.
     */
    public static BasicKeyChain fromProtobufUnencrypted(List<Protos.Key> keys) throws UnreadableWalletException {
        BasicKeyChain chain = new BasicKeyChain();
        chain.deserializeFromProtobuf(keys);
        return chain;
    }

    /**
     * Returns a new BasicKeyChain that contains all basic, ORIGINAL type keys and also any encrypted keys extracted
     * from the list. Unrecognised key types are ignored.
     * @throws com.google.bitcoin.store.UnreadableWalletException.BadPassword if the password doesn't seem to match
     * @throws com.google.bitcoin.store.UnreadableWalletException if the data structures are corrupted/inconsistent
     */
    public static BasicKeyChain fromProtobufEncrypted(List<Protos.Key> keys, KeyCrypter crypter) throws UnreadableWalletException {
        BasicKeyChain chain = new BasicKeyChain(checkNotNull(crypter));
        chain.deserializeFromProtobuf(keys);
        return chain;
    }

    private void deserializeFromProtobuf(List<Protos.Key> keys) throws UnreadableWalletException {
        lock.lock();
        try {
            checkState(hashToKeys.isEmpty(), "Tried to deserialize into a non-empty chain");
            for (Protos.Key key : keys) {
                if (key.getType() != Protos.Key.Type.ORIGINAL && key.getType() != Protos.Key.Type.ENCRYPTED_SCRYPT_AES)
                    continue;
                boolean encrypted = key.getType() == Protos.Key.Type.ENCRYPTED_SCRYPT_AES;
                byte[] priv = key.hasSecretBytes() ? key.getSecretBytes().toByteArray() : null;
                if (!key.hasPublicKey())
                    throw new UnreadableWalletException("Public key missing");
                byte[] pub = key.getPublicKey().toByteArray();
                ECKey ecKey;
                if (encrypted) {
                    checkState(keyCrypter != null, "This wallet is encrypted but encrypt() was not called prior to deserialization");
                    if (!key.hasEncryptedData())
                        throw new UnreadableWalletException("Encrypted private key data missing");
                    Protos.EncryptedData proto = key.getEncryptedData();
                    EncryptedData e = new EncryptedData(proto.getInitialisationVector().toByteArray(),
                            proto.getEncryptedPrivateKey().toByteArray());
                    ecKey = ECKey.fromEncrypted(e, keyCrypter, pub);
                } else {
                    if (priv != null)
                        ecKey = ECKey.fromPrivateAndPrecalculatedPublic(priv, pub);
                    else
                        ecKey = ECKey.fromPublicOnly(pub);
                }
                ecKey.setCreationTimeSeconds((key.getCreationTimestamp() + 500) / 1000);
                importKeyLocked(ecKey);
            }
        } finally {
            lock.unlock();
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // Event listener support
    //
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void addEventListener(KeyChainEventListener listener) {
        addEventListener(listener, Threading.USER_THREAD);
    }

    @Override
    public void addEventListener(KeyChainEventListener listener, Executor executor) {
        listeners.add(new ListenerRegistration<KeyChainEventListener>(listener, executor));
    }

    @Override
    public boolean removeEventListener(KeyChainEventListener listener) {
        return ListenerRegistration.removeFromList(listener, listeners);
    }

    private void queueOnKeysAdded(final List<ECKey> keys) {
        checkState(lock.isHeldByCurrentThread());
        for (final ListenerRegistration<KeyChainEventListener> registration : listeners) {
            registration.executor.execute(new Runnable() {
                @Override
                public void run() {
                    registration.listener.onKeysAdded(keys);
                }
            });
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // Encryption support
    //
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Convenience wrapper around {@link #toEncrypted(com.google.bitcoin.crypto.KeyCrypter,
     * org.spongycastle.crypto.params.KeyParameter)} which uses the default Scrypt key derivation algorithm and
     * parameters, derives a key from the given password and returns the created key.
     */
    @Override
    public BasicKeyChain toEncrypted(CharSequence password) {
        checkNotNull(password);
        checkArgument(password.length() > 0);
        KeyCrypter scrypt = new KeyCrypterScrypt();
        KeyParameter derivedKey = scrypt.deriveKey(password);
        return toEncrypted(scrypt, derivedKey);
    }

    /**
     * Encrypt the wallet using the KeyCrypter and the AES key. A good default KeyCrypter to use is
     * {@link com.google.bitcoin.crypto.KeyCrypterScrypt}.
     *
     * @param keyCrypter The KeyCrypter that specifies how to encrypt/ decrypt a key
     * @param aesKey AES key to use (normally created using KeyCrypter#deriveKey and cached as it is time consuming
     *               to create from a password)
     * @throws KeyCrypterException Thrown if the wallet encryption fails. If so, the wallet state is unchanged.
     */
    @Override
    public BasicKeyChain toEncrypted(KeyCrypter keyCrypter, KeyParameter aesKey) {
        lock.lock();
        try {
            checkNotNull(keyCrypter);
            checkState(this.keyCrypter == null, "Key chain is already encrypted");
            BasicKeyChain encrypted = new BasicKeyChain(keyCrypter);
            for (ECKey key : hashToKeys.values()) {
                ECKey encryptedKey = key.encrypt(keyCrypter, aesKey);
                // Check that the encrypted key can be successfully decrypted.
                // This is done as it is a critical failure if the private key cannot be decrypted successfully
                // (all bitcoin controlled by that private key is lost forever).
                // For a correctly constructed keyCrypter the encryption should always be reversible so it is just
                // being as cautious as possible.
                if (!ECKey.encryptionIsReversible(key, encryptedKey, keyCrypter, aesKey))
                    throw new KeyCrypterException("The key " + key.toString() + " cannot be successfully decrypted after encryption so aborting wallet encryption.");
                encrypted.importKeyLocked(encryptedKey);
            }
            return encrypted;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public BasicKeyChain toDecrypted(CharSequence password) {
        checkNotNull(keyCrypter, "Wallet is already decrypted");
        KeyParameter aesKey = keyCrypter.deriveKey(password);
        return toDecrypted(aesKey);
    }

    @Override
    public BasicKeyChain toDecrypted(KeyParameter aesKey) {
        lock.lock();
        try {
            checkState(keyCrypter != null, "Wallet is already decrypted");
            // Do an up-front check.
            if (!checkAESKey(aesKey))
                throw new KeyCrypterException("Password/key was incorrect.");
            BasicKeyChain decrypted = new BasicKeyChain();
            for (ECKey key : hashToKeys.values()) {
                decrypted.importKeyLocked(key.decrypt(keyCrypter, aesKey));
            }
            return decrypted;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns whether the given password is correct for this key chain.
     * @throws IllegalStateException if the chain is not encrypted at all.
     */
    @Override
    public boolean checkPassword(CharSequence password) {
        checkNotNull(password);
        checkState(keyCrypter != null, "Key chain not encrypted");
        return checkAESKey(keyCrypter.deriveKey(password));
    }

    /**
     * Check whether the AES key can decrypt the first encrypted key in the wallet.
     *
     * @return true if AES key supplied can decrypt the first encrypted private key in the wallet, false otherwise.
     */
    @Override
    public boolean checkAESKey(KeyParameter aesKey) {
        lock.lock();
        try {
            // If no keys then cannot decrypt.
            if (hashToKeys.isEmpty()) return false;
            checkState(keyCrypter != null, "Key chain is not encrypted");

            // Find the first encrypted key in the wallet.
            ECKey first = null;
            for (ECKey key : hashToKeys.values()) {
                if (key.isEncrypted()) {
                    first = key;
                    break;
                }
            }
            checkState(first != null, "No encrypted keys in the wallet");

            try {
                ECKey rebornKey = first.decrypt(keyCrypter, aesKey);
                return Arrays.equals(first.getPubKey(), rebornKey.getPubKey());
            } catch (KeyCrypterException e) {
                // The AES key supplied is incorrect.
                return false;
            }
        } finally {
            lock.unlock();
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // Bloom filtering support
    //
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////


    @Override
    public BloomFilter getFilter(int size, double falsePositiveRate, long tweak) {
        BloomFilter filter = new BloomFilter(size, falsePositiveRate, tweak);
        for (ECKey key : hashToKeys.values()) {
            filter.insert(key.getPubKey());
            filter.insert(key.getPubKeyHash());
        }
        return filter;
    }

    @Override
    public int numBloomFilterEntries() {
        return numKeys() * 2;
    }
}
