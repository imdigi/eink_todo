package cn.inktodo;

import com.nlf.calendar.Solar;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Chinese calendar convention: a term applies for its entire civil day and until the next term. */
final class SolarTerm {
    static String name(LocalDate day) {
        return Solar.fromYmd(day.getYear(),day.getMonthValue(),day.getDayOfMonth()).getLunar().getPrevJieQi(true).getName();
    }
    static String date(LocalDate day) {
        return day.format(DateTimeFormatter.ofPattern("MM月dd日",Locale.CHINA));
    }
}
