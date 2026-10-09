/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026  miron404                                               *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <https://www.gnu.org/licenses/>.      *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.masque;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class MasqueBean extends AbstractBean {

    public static final String MODE_QUIC = "quic";
    public static final String MODE_HTTP2 = "http2";

    public static final String DEFAULT_SNI = "consumer-masque.cloudflareclient.com";
    /**
     * Cloudflare does not hand out an HTTP/2 endpoint at enrollment.
     * See https://github.com/Diniboy1123/usque/wiki/HTTP-2-support
     */
    public static final String DEFAULT_HTTP2_ADDRESS = "162.159.198.2";

    /**
     * Four minutes keeps the mapping of most carrier NATs alive at a
     * sixteenth of the wakeups Go's default of 15 seconds costs.
     */
    public static final int DEFAULT_TCP_KEEPALIVE_PERIOD = 240;

    /**
     * Endpoint used in HTTP/2 mode. Empty falls back to {@link #DEFAULT_HTTP2_ADDRESS}.
     */
    public String http2Address;
    /**
     * Base64 DER of the enrolled ECDSA P-256 private key.
     */
    public String privateKey;
    /**
     * PEM PKIX public key of the endpoint, pinned during the handshake.
     */
    public String endpointPublicKey;
    /**
     * Addresses assigned to this device inside the tunnel, one per line.
     */
    public String localAddress;
    public String sni;
    public String mode;
    public Integer mtu;
    public Integer keepalivePeriod;
    public Integer initialPacketSize;
    /**
     * Seconds the HTTP/2 connection may receive nothing before it is pinged.
     * 0 leaves it unchecked. QUIC uses {@link #keepalivePeriod} instead.
     */
    public Integer http2PingPeriod;
    /**
     * Seconds the HTTP/2 connection may sit idle before the kernel probes it.
     * 0 turns the probes off. QUIC uses {@link #keepalivePeriod} instead.
     */
    public Integer tcpKeepalivePeriod;
    public Boolean allowInsecure;

    @Override
    public void initializeDefaultValues() {
        // Set before the superclass, which would otherwise fall back to 1080.
        if (serverPort == null) serverPort = 443;
        super.initializeDefaultValues();
        if (http2Address == null) http2Address = "";
        if (privateKey == null) privateKey = "";
        if (endpointPublicKey == null) endpointPublicKey = "";
        if (localAddress == null) localAddress = "";
        if (sni == null) sni = DEFAULT_SNI;
        if (mode == null) mode = MODE_QUIC;
        if (mtu == null) mtu = 1280;
        if (keepalivePeriod == null) keepalivePeriod = 30;
        if (initialPacketSize == null) initialPacketSize = 0;
        if (http2PingPeriod == null) http2PingPeriod = 0;
        if (tcpKeepalivePeriod == null) tcpKeepalivePeriod = DEFAULT_TCP_KEEPALIVE_PERIOD;
        if (allowInsecure == null) allowInsecure = false;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        super.serialize(output);
        output.writeInt(2);
        output.writeString(http2Address);
        output.writeString(privateKey);
        output.writeString(endpointPublicKey);
        output.writeString(localAddress);
        output.writeString(sni);
        output.writeString(mode);
        output.writeInt(mtu);
        output.writeInt(keepalivePeriod);
        output.writeInt(initialPacketSize);
        output.writeBoolean(allowInsecure);
        output.writeInt(http2PingPeriod);
        output.writeInt(tcpKeepalivePeriod);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        super.deserialize(input);
        int version = input.readInt();
        http2Address = input.readString();
        privateKey = input.readString();
        endpointPublicKey = input.readString();
        localAddress = input.readString();
        sni = input.readString();
        mode = input.readString();
        mtu = input.readInt();
        keepalivePeriod = input.readInt();
        initialPacketSize = input.readInt();
        allowInsecure = input.readBoolean();
        if (version >= 1) {
            http2PingPeriod = input.readInt();
        }
        if (version >= 2) {
            tcpKeepalivePeriod = input.readInt();
        }
    }

    @Override
    public boolean isInsecure() {
        return allowInsecure;
    }

    @NonNull
    @Override
    public MasqueBean clone() {
        return KryoConverters.deserialize(new MasqueBean(), KryoConverters.serialize(this));
    }

    public static final Creator<MasqueBean> CREATOR = new CREATOR<>() {
        @NonNull
        @Override
        public MasqueBean newInstance() {
            return new MasqueBean();
        }

        @Override
        public MasqueBean[] newArray(int size) {
            return new MasqueBean[size];
        }
    };

}
