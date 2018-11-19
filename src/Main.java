import java.io.*;

public class Main {
	public static void main(String[] args){
		String srcPath = "", dstPath = "", patchPath ="";

		BinDiffApplier applier;

		boolean haveSource = false;

		if (args.length <= 1 || args.length > 3) {
			System.out.println("Parameters: <source> <patch> <outfile>");
			return;
		} else if (args.length==2){
			patchPath = args[0];
			dstPath = args[1];
		} else {
			srcPath = args[0];
			patchPath = args[1];
			dstPath = args[2];
			haveSource = true;
		}

		try {
			FileInputStream patch = new FileInputStream(patchPath);
			RandomAccessFile dst = new RandomAccessFile(dstPath, "rw");
			FileInputStream src = null;
			if (haveSource) {
				src = new FileInputStream(srcPath);
			}
			applier = new BinDiffApplier(patch, src, dst);
			applier.apply(new BinDiffApplier.Options().SetPatchHasHeader(true));
			System.out.println("Done.");
		} catch (IOException | PatchHeaderException | PatchDataException ex) {
			System.out.println(ex.toString());
			System.exit(1);
		}
	}
}
