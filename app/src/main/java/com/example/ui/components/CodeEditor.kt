package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import java.util.regex.Pattern

class SyntaxHighlightTransformation(val language: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val builder = AnnotatedString.Builder(text.text)
        val styleString = text.text
        
        if (language.equals("json", ignoreCase = true)) {
            // Highlighting JSON
            // 1. Strings (including keys)
            val stringPattern = Pattern.compile("\"(?:\\\\\"|[^\"])*\"")
            val matcherStrings = stringPattern.matcher(styleString)
            while (matcherStrings.find()) {
                val start = matcherStrings.start()
                val end = matcherStrings.end()
                val isKey = end < styleString.length && styleString[end] == ':' || 
                            (end + 1 < styleString.length && styleString[end + 1] == ':') ||
                            (end + 2 < styleString.length && styleString[end + 2] == ':')
                val color = if (isKey) MetallicGold else Color(0xFF4ADE80)
                builder.addStyle(SpanStyle(color = color), start, end)
            }
            
            // 2. Numbers
            val numPattern = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b")
            val matcherNum = numPattern.matcher(styleString)
            while (matcherNum.find()) {
                builder.addStyle(SpanStyle(color = Color(0xFF60A5FA)), matcherNum.start(), matcherNum.end())
            }
            
            // 3. Booleans and Null
            val boolPattern = Pattern.compile("\\b(true|false|null)\\b")
            val matcherBool = boolPattern.matcher(styleString)
            while (matcherBool.find()) {
                builder.addStyle(SpanStyle(color = Color(0xFFF59E0B)), matcherBool.start(), matcherBool.end())
            }
            
            // 4. Curly braces and brackets
            val puncPattern = Pattern.compile("[\\{\\}\\[\\]]")
            val matcherPunc = puncPattern.matcher(styleString)
            while (matcherPunc.find()) {
                builder.addStyle(SpanStyle(color = Color(0xFFEC4899), fontWeight = FontWeight.Bold), matcherPunc.start(), matcherPunc.end())
            }
        } else if (language.equals("css", ignoreCase = true)) {
            // Highlighting CSS -> Comments
            val commentPattern = Pattern.compile("/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/")
            val matcherComment = commentPattern.matcher(styleString)
            while (matcherComment.find()) {
                builder.addStyle(SpanStyle(color = TextMuted), matcherComment.start(), matcherComment.end())
            }
            
            // Selectors
            val selectorPattern = Pattern.compile("([^\\{\\}]+)\\s*(?=\\{)")
            val matcherSelector = selectorPattern.matcher(styleString)
            while (matcherSelector.find()) {
                builder.addStyle(SpanStyle(color = BrightGold), matcherSelector.start(), matcherSelector.end())
            }
            
            // Properties
            val propPattern = Pattern.compile("(?<=\\{|;)[\\s\\n\\r]*([a-zA-Z\\- ]+)(?=\\s*:)")
            val matcherProp = propPattern.matcher(styleString)
            while (matcherProp.find()) {
                try {
                    builder.addStyle(SpanStyle(color = Color(0xFF60A5FA)), matcherProp.start(1), matcherProp.end(1))
                } catch (e: Exception) {}
            }
            
            // Curly braces
            val bracePattern = Pattern.compile("[\\{\\}]")
            val matcherBrace = bracePattern.matcher(styleString)
            while (matcherBrace.find()) {
                builder.addStyle(SpanStyle(color = Color(0xFFEC4899), fontWeight = FontWeight.Bold), matcherBrace.start(), matcherBrace.end())
            }
        }
        
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

@Composable
fun CodeEditor(
    value: String,
    onValueChange: (String) -> Unit,
    language: String, // "json" or "css"
    modifier: Modifier = Modifier,
    placeholder: String = ""
) {
    val lineCount = value.split('\n').size.coerceAtLeast(1)
    val lineNumbersText = (1..lineCount).joinToString("\n") { it.toString() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CardSlateBg, RoundedCornerShape(8.dp))
            .border(1.dp, GlassBorder, RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        // Line numbers side column
        Text(
            text = lineNumbersText,
            color = TextMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp,
            modifier = Modifier
                .width(28.dp)
                .padding(end = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )

        // Splitter line
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(GlassBorder)
                .padding(horizontal = 4.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // BasicTextField for complete control and seamless integration with custom transformation
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    text = placeholder,
                    color = TextMuted.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp
                )
            }
            
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(
                    color = TextSilver,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp
                ),
                cursorBrush = SolidColor(MetallicGold),
                visualTransformation = SyntaxHighlightTransformation(language)
            )
        }
    }
}
