/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.network.protocol;

import com.google.common.base.Objects;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.spark.network.buffer.ManagedBuffer;
import org.apache.spark.network.util.DigestUtils;

/**
 * Response to {@link StreamRequest} when the stream has been successfully opened.
 * <p>
 * Note the message itself does not contain the stream data. That is written separately by the
 * sender. The receiver is expected to set a temporary channel handler that will consume the
 * number of bytes this message says the stream has.
 */
public final class DigestStreamResponse extends AbstractResponseMessage {
  public final String streamId;
  public final long byteCount;
  public final ByteBuf digestBuf;
  public final int digestLength;

  public DigestStreamResponse(String streamId, long byteCount, ManagedBuffer buffer) {
    super(buffer, false);
    this.streamId = streamId;
    this.byteCount = byteCount;
    this.digestLength = 0;
    this.digestBuf = Unpooled.buffer(0);
  }

  public DigestStreamResponse(String streamId, long byteCount, ManagedBuffer buffer, ByteBuf digestBuf) {
    super(buffer, false);
    this.streamId = streamId;
    this.byteCount = byteCount;
    this.digestBuf = digestBuf;
    this.digestLength = digestBuf.readableBytes();
  }

  @Override
  public Type type() { return Type.DigestStreamResponse; }

  @Override
  public int encodedLength() {
    return 8 + Encoders.Strings.encodedLength(streamId) + 4 + digestLength;
  }

  /** Encoding does NOT include 'buffer' itself. See {@link MessageEncoder}. */
  @Override
  public void encode(ByteBuf buf) {
    Encoders.Strings.encode(buf, streamId);
    buf.writeLong(byteCount);
    buf.writeInt(digestLength);
    buf.writeBytes(digestBuf.array());
  }

  @Override
  public ResponseMessage createFailureResponse(String error) {
    return new DigestStreamFailure(streamId, error);
  }

  public static DigestStreamResponse decode(ByteBuf buf) {
    String streamId = Encoders.Strings.decode(buf);
    long byteCount = buf.readLong();
    int digestLength = buf.readInt();
    byte[] digest = new byte[digestLength];
    buf.readBytes(digest);
    ByteBuf digestBuf = Unpooled.wrappedBuffer(digest);
    // retain the digestBuf, and should release it
    digestBuf.retain();
    return new DigestStreamResponse(streamId, byteCount, null, digestBuf);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(byteCount, streamId, body(), digestLength, digestBuf);
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof DigestStreamResponse) {
      DigestStreamResponse o = (DigestStreamResponse) other;
      return byteCount == o.byteCount && streamId.equals(o.streamId)
              && digestLength == o.digestLength && DigestUtils.digestEqual(digestBuf.array(), o.digestBuf.array());
    }
    return false;
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(this)
      .add("streamId", streamId)
      .add("byteCount", byteCount)
      .add("digestLength", digestLength)
      .add("digestHex", DigestUtils.encodeHex(digestBuf.array()))
      .add("body", body())
      .toString();
  }

}
