package com.github.hgdcoder.utils;

public class StringUtil {
    public static boolean isBlank(String str) {
        if(str==null||str.length()==0){
            return true;
        }
        for(int i=0;i<str.length();i++){
            if(!Character.isWhitespace(str.charAt(i))){
                return false;
            }
        }
        return true;
    }
}
