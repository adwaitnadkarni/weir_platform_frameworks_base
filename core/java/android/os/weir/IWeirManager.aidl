package android.os.weir;


/** @hide */

interface IWeirManager
{
	void hello();
	void createTag(String tagName, boolean globalPos, boolean globalNeg, in String[] posCapAlloc, in String[] negCapAlloc, in String[] domains);
	void addTagToLabel(String ownerPackageName, String tagName);
	boolean checkAdd(String ownerPackageName, String tagName);
}
