package sap.utilities;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;


public class CharsetConversion {
	public static void main(String[] args) throws CharacterCodingException {
		 Charset charset = Charset.forName("UTF-8");
		 CharsetDecoder chdecoder = StandardCharsets.UTF_16BE.newDecoder();
		 CharsetEncoder chencoder = StandardCharsets.UTF_8.newEncoder();
		 String s = "Había áéíóúñÑü";
		 CharBuffer charBuffer = CharBuffer.wrap(s);
		 ByteBuffer newByteBuff   = chencoder.encode(charBuffer); 
		 CharBuffer newCharBuffer = chdecoder.decode(newByteBuff);
		 while(newCharBuffer.hasRemaining()){
	        	char ch = (char) newCharBuffer.get();
	        	System.out.print(ch);
	     }
		 newCharBuffer.clear();
		 /**
		 ByteBuffer byteBuffer= ByteBuffer.wrap(s.getBytes());
		 ByteBuffer newByteBuff = chencoder.encode(s); 
		 CharBuffer charBuffer = chdecoder.decode(byteBuffer);
		 while(newByteBuff.hasRemaining()){
	        	char ch = (char) newByteBuff.get();
	        	System.out.print(ch);
	     }
		 newByteBuff.clear();
		 **/	
	}
}