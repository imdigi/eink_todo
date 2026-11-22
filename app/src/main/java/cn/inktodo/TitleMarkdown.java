package cn.inktodo;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.*;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.ext.gfm.strikethrough.*;
import java.util.Collections;

/** Native text spans: no WebView, remote resources or HTML execution. */
final class TitleMarkdown {
    static final String DEFAULT="未了之事，次第而行";
    private static final Parser PARSER=Parser.builder()
        .extensions(Collections.singletonList(StrikethroughExtension.create())).build();
    static CharSequence render(String source) {
        SpannableStringBuilder out=new SpannableStringBuilder();
        PARSER.parse(source).accept(new AbstractVisitor(){
            private void span(Node node,Object style){
                int start=out.length();visitChildren(node);
                if(out.length()>start)out.setSpan(style,start,out.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            private void blockEnd(Node node){if(node.getNext()!=null)out.append('\n');}
            @Override public void visit(Text node){out.append(node.getLiteral());}
            @Override public void visit(SoftLineBreak node){out.append('\n');}
            @Override public void visit(HardLineBreak node){out.append('\n');}
            @Override public void visit(Paragraph node){visitChildren(node);blockEnd(node);}
            @Override public void visit(Heading node){
                int start=out.length();span(node,new StyleSpan(Typeface.BOLD));
                float scale=node.getLevel()==1?1.4f:node.getLevel()==2?1.2f:1.05f;
                if(out.length()>start)out.setSpan(new RelativeSizeSpan(scale),start,out.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                blockEnd(node);
            }
            @Override public void visit(Emphasis node){span(node,new StyleSpan(Typeface.ITALIC));}
            @Override public void visit(StrongEmphasis node){span(node,new StyleSpan(Typeface.BOLD));}
            @Override public void visit(Code node){
                int start=out.length();out.append(node.getLiteral());
                if(out.length()>start)out.setSpan(new TypefaceSpan("monospace"),start,out.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            @Override public void visit(CustomNode node){
                if(node instanceof Strikethrough)span(node,new StrikethroughSpan());else visitChildren(node);
            }
            @Override public void visit(HtmlInline node){out.append(node.getLiteral());}
            @Override public void visit(HtmlBlock node){out.append(node.getLiteral());blockEnd(node);}
            @Override public void visit(ThematicBreak node){out.append("────");blockEnd(node);}
        });
        if(DEFAULT.equals(source))out.setSpan(new StyleSpan(Typeface.BOLD),0,out.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }
}
