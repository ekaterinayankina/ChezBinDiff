import java.io.EOFException;
import java.io.InputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.CRC32;

class PatchHeader {
	private static final char[] MAGIC = { 'C', 'B', 'D', '=' };
	private static int GetVersion(int v1, int v2) {
		return ((v2) << 8) | (v1);
	}

	// For version = n.m VERSION is (int)(((char)'m' << 8) | (char)'n')
	// For version = 1.2 VERSION is (int)(((char)'2' << 8) | (char)'1')
	private static final int VERSION = (('2' << 8) | '1');
	static final int FLAG_HAS_SOURCE =     1 << 3;
	static final int FLAG_SRC_COPY =	   1 << 7;
	
	private final char[] magic= new char[4]; // see MAGIC
	private int version; // see FORMAT_VERSION
	int flags; // see FLAG_xxx
	long srcLength; // source file size
	long dstLength; // dest file size
	int srcVersion; // source version code (incremental)
	int dstVersion; // dest version code (incremental)
	private long instructionCount; // instruction count in the patch
	private long instructionCRC32; // sum of instruction data CRC32
	long srcCRC32; // src file CRC32
	long dstCRC32; // dest file CRC32
	private long hdrCRC32; // header CRC32, without this field
	
	private long calculatedCRC32;
	
	public static final int SIZE = 40;
	private byte[] buffer = new byte[SIZE];
	private byte count;
	public PatchHeader(InputStream input) throws IOException {
		
		if (input.read(buffer, 0, SIZE) == -1)
			throw new EOFException("Unexpected end of patch file. Can not read header.");
		
		for (count=0; count<4;count++) {
			magic[count] = (char)buffer[count];
		}
		version = get2Bytes();
		flags = get2Bytes();
		srcLength = get4Bytes();
		dstLength = get4Bytes();
        srcVersion = get2Bytes();
        dstVersion = get2Bytes();
        instructionCount = get4Bytes();
        instructionCRC32 = get4Bytes();
        srcCRC32 = get4Bytes();
        dstCRC32 = get4Bytes();
        hdrCRC32 = get4Bytes();

		final int SIZE_WITHOUT_HEADER_CRC32 = 36;
        CRC32 crc32 = new CRC32();
        crc32.update(buffer, 0, SIZE_WITHOUT_HEADER_CRC32);
        calculatedCRC32 = crc32.getValue();
        
        buffer = null;
	}
	
	private long get4Bytes() {
		long result = (buffer[count+3] & 0xFFL) << 24 | (buffer[count+2] & 0xFFL) << 16 |
				(buffer[count+1] & 0xFFL) << 8 | (buffer[count] & 0xFFL);
		count += 4;
		return result;			
	}
	private int get2Bytes() {
		int result = (buffer[count+1] & 0xFF) << 8 | (buffer[count] & 0xFF);
		count += 2;
		return result;			
	}
	
	public boolean isVersionValid() {
		return version <= VERSION;
	}
	public boolean isMagicValid() {
		return Arrays.equals(magic, MAGIC);
	}
	public boolean isHeaderCRC32Valid() {
		return hdrCRC32 == calculatedCRC32;
	}
	public boolean isSourceCRC32Valid(long crc32) {
		return srcCRC32 == crc32;
	}
	public boolean isInstructionsCRC32Valid(long crc32) {
		return instructionCRC32 == crc32;
	}
}
