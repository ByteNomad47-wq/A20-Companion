// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.repair;
import java.security.MessageDigest;
final class RepairHash {
 static String hash(byte[] data, String algorithm) throws Exception {
  byte[] digest=MessageDigest.getInstance(algorithm).digest(data);
  StringBuilder out=new StringBuilder();
  for(byte b:digest) out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
  return out.toString();
 }
}
