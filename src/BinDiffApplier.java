import java.io.*;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;



public class BinDiffApplier {
	private static final int INSTANT_CODE_MAX = 239;
	private static final int DEFAULT_BUFFER_SIZE = 1024 * 128;

	private static final int INSTRUCTION_CODE_TYPE_DATA = 0;
	private static final int INSTRUCTION_CODE_TYPE_SOURCE = 1;
	private static final int INSTRUCTION_CODE_TYPE_DEST = 2;
	private static final int INSTRUCTION_CODE_TYPE_REPEAT = 3;

	private static final int INSTRUCTION_CODE_VALUE_OFFSET = 240;
	private static final int INSTRUCTION_LENGTH_OFFSET = 2;
	private static final int INSTRUCTION_LENGTH_MASK = 0x3 << INSTRUCTION_LENGTH_OFFSET; // 0000 1100
	private static final int INSTRUCTION_CODE_MASK =   0xFF & ~INSTRUCTION_LENGTH_MASK; // 1111 0011
	
	// shifted by 2 bits (INSTRUCTION_LENGTH_MASK)
	private static final short INSTRUCTION_CODE_LENGTH_eq0 = 0;
	private static final short INSTRUCTION_CODE_LENGTH_8bit = 1;
	private static final short INSTRUCTION_CODE_LENGTH_16bit = 2;
	private static final short INSTRUCTION_CODE_LENGTH_32bit = 3;

	// at this data length (CBDI_CODE_TYPE_SOURCE, CBDI_CODE_TYPE_DEST) starts to be more efficeint than CBDI_CODE_TYPE_DATA
	private static final int SRCDST_OVER_DATA_STARTING_LENGTH_THRESHOLD = 7;
	private static final int INSTRUCTION_CODE_LIMIT = 16;
	private static final short CBDI_CODE_TYPE_INSTANT_MIN = 0;
	private static final short CBDI_CODE_TYPE_INSTANT_MAX = 0xFF - INSTRUCTION_CODE_LIMIT;

	private final PatchHeader header;
	private final FileInputStream src;
	private final RandomAccessFile dst;
	private final FileInputStream patch;

	private CRC32 dstCRC;
	private long nDstLength;

	public BinDiffApplier(FileInputStream patch, FileInputStream src, RandomAccessFile dst) throws IOException {
		header = new PatchHeader(patch);
		this.src = src;
		this.dst = dst;
		this.patch = patch;
	}

	public void apply(Options options) throws IOException, PatchHeaderException, PatchDataException {
		int srcCodeLength;
		if (options.patchHasHeader) {
			checkPatchHeader(options);
			srcCodeLength = detectCodeLength(header.srcLength);
		} else {
			long srcLength = getFileLength(src);
			srcCodeLength = detectCodeLength(srcLength);
		}
		writeDst(options, srcCodeLength);
		options.outDstCRC32 = dstCRC.getValue();
		options.outDstLength = nDstLength;

		if (options.patchHasHeader) {
			checkDst(options);
		}
	}

	private void checkDst(Options options) throws PatchHeaderException {
		if (header.dstLength != options.outDstLength) {
			throw new PatchHeaderException("dst length " + header.dstLength + " " + options.outDstLength);
		} else if (header.dstCRC32 != options.outDstCRC32) {
			throw new PatchHeaderException("dst crc32");
		}
	}

	private int detectCodeLength (long length){
		if (length == 0)
			return INSTRUCTION_CODE_LENGTH_eq0;
		if (length <= 0xFF)
		return INSTRUCTION_CODE_LENGTH_8bit;
			else if (length <= 0xFFFF)
		return INSTRUCTION_CODE_LENGTH_16bit;
			else
		return INSTRUCTION_CODE_LENGTH_32bit;
	}

	private void checkPatchHeader(Options options) throws IOException, PatchHeaderException {
		if (!header.isMagicValid()) {
			throw new PatchHeaderException("Invalid patch file. Header check: magic word mismatch.");
		} else if (!header.isVersionValid()) {
			throw new PatchHeaderException("Unsupported patch file. Header check: patch format version is not supported by this application.");
		} else if (!header.isHeaderCRC32Valid()) {
			throw new PatchHeaderException("Invalid patch file. Header check: Header CRC check failed.");
		} else if (options.checkSrcVersion >= 0 && options.checkSrcVersion > header.srcVersion) {
			throw new PatchHeaderException("Improper patch file. Header check: source file version mismatch.");
		} else if (options.checkDstVersion >= 0 && options.checkDstVersion > header.dstVersion) {
			throw new PatchHeaderException("Improper patch file. Header check: dest file version mismatch.");
		}
		if ((header.flags & PatchHeader.FLAG_HAS_SOURCE)!=0) {

			if (getFileLength(src) != header.srcLength) {
				throw new PatchHeaderException("Patch is not suitable for source file. Header check: source file length mismatch.");
			}
			if (options.checkSourceCRC) {
				long crc32 = (options.sourceCRC != -1L) ? options.sourceCRC : getFileCRC32(src);

				if (!header.isSourceCRC32Valid(crc32)) {
					throw new PatchHeaderException("Patch is not suitable for source file. Header check: source file CRC mismatch.");
				}
			}
		}
		if ((header.flags & PatchHeader.FLAG_SRC_COPY) != 0) {
			if (header.srcCRC32 != header.dstCRC32 || header.srcLength != header.dstLength) {
				throw new PatchHeaderException("Invalid patch file. Header check: invalid field data.");
			}
			options.outDstCRC32 = header.dstCRC32;
			options.outDstLength = header.dstLength;
		}

		if (options.checkPatchDataCRC) {
			if (!header.isInstructionsCRC32Valid(getFileCRC32(patch))) {
				throw new PatchHeaderException("Patch is not suitable for source file. Header check: source file CRC mismatch.");
			}
		}
	}

	private void writeDst(Options options, int srcCodeLength) throws IOException, PatchDataException {
		dstCRC = new CRC32();
		long nSrcLength = 0;

		patch.getChannel().position(PatchHeader.SIZE);
		dst.getChannel().position(0);
		if (src != null) {
			src.getChannel().position(0);
			nSrcLength = getFileLength(src);
		}
		dst.setLength(0);

		int code;
		//данные для повтора
		int pRepCodeType = INSTRUCTION_CODE_TYPE_REPEAT;
		long nRepOffset = 0;
		long nRepLength = 0;
		short nRepInstByte = 0xFF;
		long pos;
		ByteBuffer buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
		while ((code = patch.read()) != -1){
			if (code <= INSTANT_CODE_MAX) {
				if (buffer.remaining() > 0) {
					buffer.put((byte)code);
				} else {
					int dataLength = buffer.position();
					buffer.position(0);
					byte[] data = new byte[dataLength];
					buffer.get(data);
					buffer.clear();
					dst.write(data);
					dstCRC.update(data);
					nDstLength += dataLength;
				}
			} 
			else {
				int bufferDataLength = buffer.position();
				if (bufferDataLength > 0) {
					buffer.position(0);
					byte[] data = new byte[bufferDataLength];
					buffer.get(data);
					buffer.clear();
					dst.write(data);
					dstCRC.update(data);
					nDstLength += bufferDataLength;
					pRepCodeType = INSTRUCTION_CODE_TYPE_REPEAT;
					nRepInstByte =  (short)(data[bufferDataLength - 1] & 0xFF);
				}

				code -= INSTRUCTION_CODE_VALUE_OFFSET;


				int codeType = code & INSTRUCTION_CODE_MASK;
				int codeLength = (code & INSTRUCTION_LENGTH_MASK) >> INSTRUCTION_LENGTH_OFFSET;
				long nDataLength = getVariableLengthValue(patch, codeLength);

				pos = patch.getChannel().position();

				switch (codeType) {
				case INSTRUCTION_CODE_TYPE_DATA:
					nDataLength += 1;
					if (options.patchHasHeader && nDstLength + nDataLength > header.dstLength) {
						throw new PatchDataException( "Corrupt patch file. Header check: dest file length mismatch.");
					}
					pRepCodeType = INSTRUCTION_CODE_TYPE_DATA;
					nRepOffset = patch.getChannel().position();
					nRepLength = nDataLength;
					copyData(patch, (int)nDataLength);
					nDstLength+=nDataLength;
				break;
				
				case INSTRUCTION_CODE_TYPE_SOURCE:
					if (src == null)
						throw new PatchDataException("Corrupt patch file. Unexpected case.");
					nDataLength += SRCDST_OVER_DATA_STARTING_LENGTH_THRESHOLD;
					if (options.patchHasHeader && nDstLength + nDataLength > header.dstLength)
						throw new PatchDataException( "Corrupt patch file. Header check: dest file length mismatch.");
					//вычисление смещения от начала в исходном файле
					long nSrcOffset = getVariableLengthValue(patch,srcCodeLength);


					//вычисление конечной позиции данных (смещение+длина), сравнение с длиной всего файла
					if (nSrcOffset + nDataLength > nSrcLength) {
						throw new PatchDataException("Corrupt patch file. Reference to source file out of bounds.");
					}
					pRepCodeType = INSTRUCTION_CODE_TYPE_SOURCE;
					nRepOffset = nSrcOffset;
					nRepLength = nDataLength;
					//копирование данных начиная со смещения nSrcOffset
					src.getChannel().position(nSrcOffset);
					copyData(src, (int)nDataLength);
					nDstLength+=nDataLength;
					break;

				case INSTRUCTION_CODE_TYPE_DEST:
					nDataLength += SRCDST_OVER_DATA_STARTING_LENGTH_THRESHOLD;
					if (options.patchHasHeader && nDstLength + nDataLength > header.dstLength)
						throw new PatchDataException( "Corrupt patch file. Header check: dest file length mismatch.");
					int dstCodeLength = detectCodeLength(nDstLength);
					//вычисление смещения от начала файла dst, по которому хранятся данные
					long nDstOffset = getVariableLengthValue(patch, dstCodeLength);
					//проверка
					if (options.patchHasHeader && (nDstOffset + nDataLength) > header.dstLength)
						throw new PatchDataException("Corrupt patch file. Reference to dest file out of bounds.");
					pRepCodeType = INSTRUCTION_CODE_TYPE_DEST;
					nRepOffset = nDstOffset;
					nRepLength = nDataLength;
					//копирование данных из файла dst с позиции nDstOffset длиной nDataLength в конец файла
					copyDestData(nDstOffset,nDataLength);
					nDstLength += nDataLength;
					break;
				case INSTRUCTION_CODE_TYPE_REPEAT:
					//если до этого не выполнялись другие операции - ошибка
					if (pRepCodeType== INSTRUCTION_CODE_TYPE_REPEAT && nRepInstByte == 0xFF)
						throw new PatchDataException("Corrupt patch file. Unexpected 'repeat' instruction. " + String.valueOf(pos));
					long nRepeats = nDataLength + 1;
					//если до этого выполнялись другие операции
					if (pRepCodeType != INSTRUCTION_CODE_TYPE_REPEAT) {
						//если длина=0 - ошибка
						if (nRepLength == 0)
							throw new PatchDataException("Corrupt patch file. Unexpected case.");
						//вычисление длины данных для записи = количество повторов*длину
						long nRepeatedDataLength = nRepeats * nRepLength;
						//проверка - поместятся ли данные
						if (options.patchHasHeader && nDstLength + nRepeatedDataLength > header.dstLength)
							throw new PatchDataException("Corrupt patch file. Header check: dest file length mismatch.");
						//перебор всех возможных вариаций повтора

						byte[] buf = new byte[(int)nRepLength];
						int numRead;
						switch (pRepCodeType) {
						case INSTRUCTION_CODE_TYPE_DEST:
							dst.getChannel().position(nRepOffset);
							numRead = dst.read(buf);
							break;
						case INSTRUCTION_CODE_TYPE_SOURCE:
							src.getChannel().position(nRepOffset);

							numRead = src.read(buf);
							break;
						case INSTRUCTION_CODE_TYPE_DATA:
							long patchPos = patch.getChannel().position();
							patch.getChannel().position(nRepOffset);
							numRead = patch.read(buf);
							patch.getChannel().position(patchPos);
							break;
						default:
							throw new PatchDataException("Corrupt patch file. Unexpected case.");
						}
						if (numRead == -1) {
							throw new PatchDataException("Unexpected end of file.");
						}
						dst.seek(dst.length());
						for (int i = 0; i < nRepeats; ++i) {
							dst.write(buf);
							dstCRC.update(buf);
						}
						nDstLength += nRepeatedDataLength;
					} else {
					//если до этого не выполнялись другие операции
						if (!(nRepInstByte >= CBDI_CODE_TYPE_INSTANT_MIN && nRepInstByte <= CBDI_CODE_TYPE_INSTANT_MAX))
							throw new PatchDataException("corr patch");

						if (options.patchHasHeader && nDstLength + nRepeats > header.dstLength)
							throw new PatchDataException("len dst");


						byte[] buf = new byte[(int)nRepeats];
						Arrays.fill(buf, (byte)nRepInstByte);
						dst.seek(dst.length());
						dst.write(buf);
						dstCRC.update(buf);
						nDstLength += nRepeats;


					}
					pRepCodeType=INSTRUCTION_CODE_TYPE_REPEAT;
					nRepInstByte = 0xFF;

				break;
				default:
					throw new PatchDataException("Corrupt patch file. Unexpected case.");
				}
			}
		}//конец файла патча
		if (buffer.position()>0){
			int dataLength = buffer.position();
			buffer.position(0);
			byte[] data = new byte[dataLength];
			buffer.get(data);
			buffer.clear();
			dst.write(data);
			dstCRC.update(data);
			nDstLength += dataLength;
		}
	}
	
	private static long getFileLength(FileInputStream file) throws IOException {
		return file.getChannel().size();
	}
	private static long getFileCRC32(FileInputStream file) throws IOException {
		CheckedInputStream crc32Stream = new CheckedInputStream(file, new CRC32());
		
		byte[]  buffer = new byte[file.available()];
		while (crc32Stream.read(buffer) != -1);
		
		return crc32Stream.getChecksum().getValue();
	}
	private static long getVariableLengthValue(FileInputStream patch, int codeLength) throws IOException {
		byte[] buffer;
		switch (codeLength) {
		case INSTRUCTION_CODE_LENGTH_eq0:
		    return 0;
		case INSTRUCTION_CODE_LENGTH_8bit:
			return patch.read();
		case INSTRUCTION_CODE_LENGTH_16bit:
            buffer = new byte[2];
            if (patch.read(buffer) < 2) {
            	return -1;
			}
            return (buffer[1] & 0xFFL) << 8 | (buffer[0] & 0xFFL);
        case INSTRUCTION_CODE_LENGTH_32bit:
        	buffer = new byte[4];
			if (patch.read(buffer) < 4) {
				return -1;
			}
			return (buffer[3] & 0xFFL) << 24 | (buffer[2] & 0xFFL) << 16 | (buffer[1] & 0xFFL) << 8 | (buffer[0] & 0xFFL);
		}
		return -1;

	}

	private void copyData(FileInputStream src, int length) throws IOException, PatchDataException {
		int bufferSize = (length > DEFAULT_BUFFER_SIZE) ? DEFAULT_BUFFER_SIZE : length;
		byte[] buffer = new byte[bufferSize];
		do {
			if (length < bufferSize) bufferSize = length;
			if (src.read(buffer, 0, bufferSize) != bufferSize) {
				throw new PatchDataException("error copy data");
			}
			dst.write(buffer, 0, bufferSize);
			dstCRC.update(buffer, 0, bufferSize);
			length -= bufferSize;
		} while (length > 0);
	}
	private void copyDestData(long nDstOffset, long length) throws IOException, PatchDataException {
		int bufferSize = ((int)length > DEFAULT_BUFFER_SIZE) ? DEFAULT_BUFFER_SIZE : (int)length;
		byte[] buffer = new byte[bufferSize];
		do {
			dst.seek(nDstOffset);
			if (length < bufferSize) bufferSize = (int)length;
			if (dst.read(buffer, 0, bufferSize) != bufferSize) {
				throw new PatchDataException("Corrupt patch file. Reference to dest file out of bounds.");
			}
			dst.seek(dst.length());
			dst.write(buffer, 0, bufferSize);
			dstCRC.update(buffer, 0, bufferSize);
			nDstOffset += bufferSize;
			length -= bufferSize;
		} while (length > 0);
	}

	static public class Options {
		boolean patchHasHeader; // CBD patch file can have 40-byte header or no. Default is 'true'.
		boolean checkSourceCRC; // Check source file CRC before patching. Only if header is available. Default is 'true'.
		boolean checkPatchDataCRC; // Check patch file CRC before patching. Only if header is available. Default is 'true'.
		long sourceCRC; // if source CRC is known, specify value here, -1L is to skip check. Default is -1L.
		int checkSrcVersion; // Check the patch version1 (src/old) value, -1 to skip check. Default is -1.
		int checkDstVersion; // Check the patch version2 (dest/new) value, -1 to skip check. Default is -1.
		long outDstCRC32; // returns CRC32 of newly created file.
		long outDstLength; // returns length of newly created file.

		public Options() {
		}

		public Options SetPatchHasHeader(boolean value) {
			patchHasHeader = value;
			return this;
		}
	}
}
