package com.nabulink.app;
import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import java.security.*;
import java.security.spec.*;
import java.io.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

final class Pairing {
    private static final String ALIAS="nabu-link-storage";
    static final int MAGIC=4;
    interface Confirm { boolean approve(String code) throws Exception; }
    static byte[] digest(byte[]... values)throws Exception{
        MessageDigest d=MessageDigest.getInstance("SHA-256");for(byte[] v:values)d.update(v);return d.digest();
    }
    static byte[] mac(byte[] key,String label,byte[] transcript)throws Exception{
        Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));m.update(label.getBytes("UTF-8"));return m.doFinal(transcript);
    }
    static byte[] read(DataInputStream in,int n)throws IOException{byte[] b=new byte[n];in.readFully(b);return b;}
    static byte[] packet(DataInputStream in)throws IOException{int n=in.readUnsignedShort();if(n<32||n>512)throw new IOException("Invalid key");return read(in,n);}
    static void write(DataOutputStream out,byte[] v)throws IOException{out.writeShort(v.length);out.write(v);out.flush();}
    static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.ROOT,"%02x",x&255));return s.toString();}
    static String exchange(DataInputStream in,DataOutputStream out,boolean host,Confirm confirm)throws Exception{
        KeyPairGenerator g=KeyPairGenerator.getInstance("EC");g.initialize(new ECGenParameterSpec("secp256r1"));KeyPair kp=g.generateKeyPair();
        byte[] pub=kp.getPublic().getEncoded(),nonce=new byte[32];new SecureRandom().nextBytes(nonce);
        byte[] commitment=digest(pub,nonce);
        out.write(commitment);out.flush();byte[] otherCommit=read(in,32);
        write(out,pub);out.write(nonce);out.flush();byte[] otherPub=packet(in),otherNonce=read(in,32);
        if(!MessageDigest.isEqual(otherCommit,digest(otherPub,otherNonce)))throw new IOException("Commitment mismatch");
        PublicKey remote=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(otherPub));
        KeyAgreement ka=KeyAgreement.getInstance("ECDH");ka.init(kp.getPrivate());ka.doPhase(remote,true);
        byte[] transcript=host?digest(otherCommit,commitment,otherPub,otherNonce,pub,nonce):digest(commitment,otherCommit,pub,nonce,otherPub,otherNonce);
        byte[] root=mac(transcript,"NabuLink pairing v1",ka.generateSecret());
        byte[] sas=mac(root,"verification",transcript);
        long n=((sas[0]&255L)<<24)|((sas[1]&255L)<<16)|((sas[2]&255L)<<8)|(sas[3]&255L);
        if(!confirm.approve(String.format(Locale.ROOT,"%06d",n%1000000)))throw new IOException("Pairing rejected");
        out.write(mac(root,host?"host approved":"client approved",transcript));out.flush();
        if(!MessageDigest.isEqual(read(in,32),mac(root,host?"client approved":"host approved",transcript)))throw new IOException("Approval mismatch");
        return hex(Arrays.copyOf(mac(root,"status key",transcript),16));
    }
    static javax.crypto.SecretKey storageKey()throws Exception{
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias(ALIAS)){
            KeyGenerator g=KeyGenerator.getInstance("AES","AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();
        }
        return (javax.crypto.SecretKey)ks.getKey(ALIAS,null);
    }
    static synchronized void save(Context c,String role,String secret)throws Exception{
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,storageKey());cipher.updateAAD(role.getBytes("UTF-8"));
        byte[] data=cipher.doFinal(secret.getBytes("UTF-8"));
        if(!c.getSharedPreferences("pairing",0).edit().putString(role,Base64.encodeToString(cipher.getIV(),2)+":"+Base64.encodeToString(data,2)).commit())throw new IOException("Cannot save pairing");
    }
    static synchronized String load(Context c,String role){
        try{String v=c.getSharedPreferences("pairing",0).getString(role,"");String[] p=v.split(":");if(p.length!=2)return null;
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,storageKey(),new GCMParameterSpec(128,Base64.decode(p[0],2)));cipher.updateAAD(role.getBytes("UTF-8"));
            String key=new String(cipher.doFinal(Base64.decode(p[1],2)),"UTF-8");return key.matches("[0-9a-f]{32}")?key:null;
        }catch(Exception e){return null;}
    }
    static synchronized void forget(Context c){c.getSharedPreferences("pairing",0).edit().clear().commit();}
}
