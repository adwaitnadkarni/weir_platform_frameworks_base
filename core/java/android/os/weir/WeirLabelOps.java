package android.os.weir;

import java.util.HashSet;
public final class WeirLabelOps {

    public static final int A_DOMINATES_B = 1;
    public static final int B_DOMINATES_A = -1;
    public static final int A_DISJOINT_B = -2;
    public static final int A_EQUALS_B = 0;

    public static int compareLabels(HashSet<Long> A, HashSet<Long> B){
	int ret;

	//null checks
	A= A!=null?A:new HashSet<Long>();
	B= B!=null?B:new HashSet<Long>();

	//Checking if A dominates B
	//For all b, is b \in A ?
	ret = A_DOMINATES_B;
	for(Long b : B){
	    if(!A.contains(b)){
		//At this point 0(equals) and 1(a dominates b) are ruled out.
		ret = B_DOMINATES_A;	
		break;
	    }
	}

	//If the first test fails, i.e., A does not dominate B, and we now want to test if ret==B_DOMINATES_A is true.
	if(ret==B_DOMINATES_A){
	    for(Long a : A){
		if(!B.contains(a)){
		    //a does not dominate b, and now even b does not dominate a
		    ret = A_DISJOINT_B;
		    break;
		}
	    }
	} else {//A_DOMINATES_B (since we set that as the initial value)
	    //Now check if B_DOMINATES_A, which means they are equal.
	    ret = A_EQUALS_B;
	    for(Long a : A){
		if(!B.contains(a)){
		    //a dominates b, but b does not dominate a
		    ret = A_DOMINATES_B;
		    break;
		}
	    }
	}

	return ret;
    }

    //an equals question; a special case of compareLabels, just an easier to use API
    public static boolean equals(HashSet<Long> A, HashSet<Long> B){
	if(compareLabels(A,B)==A_EQUALS_B)
	    return true;
	return false;
    }
}
