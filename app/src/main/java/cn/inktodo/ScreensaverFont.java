package cn.inktodo;

import android.content.*;

/** Independent snapshot typeface; one face for the entire image, defaulting to device Songti. */
final class ScreensaverFont {
    static final String FILE="screensaver_font_file",NAME="screensaver_font_name";
    static FontStore.Choice defaults(){return new FontStore.Choice("","默认宋体",SnapshotImage.headingTypeface());}
    static FontStore.Choice load(Context context){
        SharedPreferences settings=context.getSharedPreferences("settings",0);
        FontStore.Choice choice=FontStore.load(context,settings.getString(FILE,""),settings.getString(NAME,"默认宋体"));
        return choice.file.isEmpty()?defaults():choice;
    }
}
