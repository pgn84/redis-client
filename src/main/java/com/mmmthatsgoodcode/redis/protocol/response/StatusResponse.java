package com.mmmthatsgoodcode.redis.protocol.response;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufProcessor;

import com.mmmthatsgoodcode.redis.protocol.AbstractResponse;

public class StatusResponse extends AbstractResponse<String> {

	private static final Logger LOG = LoggerFactory.getLogger(StatusResponse.class);
	
	public StatusResponse(ByteBuf in) {
		super(in);
	}

	/**
	 * Expected format:
	 * +{status response}CR+LF
	 */
	@Override
	public boolean decode() {
		
		// there is at least one delimiter in the buffer - we can do the decoding
		if (this.in.forEachByte(HAS_DELIMITER) != -1) {
			byte[] statusCode = this.in.readBytes( this.in.forEachByte(HAS_DELIMITER) - this.in.readerIndex() ).array(); // read up to the new line..
			setValue(new String(statusCode));
			LOG.debug("Decoded status response: \"{}\"", value());
			return true;
		}
		
		return false;
	}	
	

}
