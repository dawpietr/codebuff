package org.antlr.codebuff;

import org.antlr.codebuff.misc.CodeBuffTokenStream;
import org.antlr.codebuff.validation.TokenPositionAnalysis;
import org.antlr.codebuff.walkers.IdentifyOversizeLists;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.WritableToken;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.misc.Pair;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.List;
import java.util.Map;
import java.util.Vector;

import static org.antlr.codebuff.Tool.levenshteinDistance;
import static org.antlr.codebuff.Tool.tokenText;
import static org.antlr.codebuff.Tool.tokenize;
import static org.antlr.codebuff.Trainer.CAT_ALIGN_WITH_ANCESTOR_CHILD;
import static org.antlr.codebuff.Trainer.CAT_INDENT;
import static org.antlr.codebuff.Trainer.CAT_INDENT_FROM_ANCESTOR_CHILD;
import static org.antlr.codebuff.Trainer.CAT_INJECT_NL;
import static org.antlr.codebuff.Trainer.CAT_INJECT_WS;
import static org.antlr.codebuff.Trainer.CAT_NO_ALIGNMENT;
import static org.antlr.codebuff.Trainer.FEATURES_ALIGN;
import static org.antlr.codebuff.Trainer.FEATURES_INJECT_WS;
import static org.antlr.codebuff.Trainer.INDEX_FIRST_ON_LINE;
import static org.antlr.codebuff.Trainer.INDEX_MATCHING_TOKEN_DIFF_LINE;
import static org.antlr.codebuff.Trainer.INDEX_PREV_FIRST_ON_LINE;
import static org.antlr.codebuff.Trainer.MAX_ALIGN_CONTEXT_DIFF_THRESHOLD;
import static org.antlr.codebuff.Trainer.MAX_WS_CONTEXT_DIFF_THRESHOLD;
import static org.antlr.codebuff.Trainer.earliestAncestorStartingWithToken;
import static org.antlr.codebuff.Trainer.getContextFeatures;
import static org.antlr.codebuff.Trainer.getMatchingSymbolOnDiffLine;
import static org.antlr.codebuff.Trainer.getRealTokens;
import static org.antlr.codebuff.Trainer.getTokensOnPreviousLine;
import static org.antlr.codebuff.Trainer.indexTree;
import static org.antlr.codebuff.Trainer.setListInfoFeatures;
import static org.antlr.codebuff.misc.BuffUtils.filter;

public class Formatter {
	public static final int WIDE_LIST_THRESHOLD = 120; // anything this big is definitely "oversize list"
	public static final int COL_ALARM_THRESHOLD = 80;
	public static final int DEFAULT_K = 23;

	public final Corpus corpus;

	public StringBuilder output;
	public CodeBuffTokenStream originalTokens; // copy of tokens with line/col info
	public List<Token> realTokens;             // just the real tokens from tokens

	/** A map from real token to node in the parse tree */
	public Map<Token, TerminalNode> tokenToNodeMap = null;

	/** analysis[i] is info about what we decided for token index i from
	 *  original stream (not index into real token list)
	 */
	public Vector<TokenPositionAnalysis> analysis;

	/** Collected for formatting (not training) by SplitOversizeLists.
	 *  Training finds split lists and normal lists. This uses list len
	 *  (in char units) to decide split or not.  If size is closest to split median,
	 *  we claim oversize list.
	 */
	public Map<Token,Pair<Boolean,Integer>> tokenToListInfo;

	public CodekNNClassifier nlwsClassifier;
	public CodekNNClassifier alignClassifier;
	public int k;
	public FeatureMetaData[] injectWSFeatures = FEATURES_INJECT_WS;
	public FeatureMetaData[] alignmentFeatures = FEATURES_ALIGN;

	public InputDocument testDoc;

	public int indentSize; // size in spaces of an indentation

	public int line = 1;
	public int charPosInLine = 0;

	public Formatter(Corpus corpus, int indentSize, int k,
	                 FeatureMetaData[] injectWSFeatures, FeatureMetaData[] alignmentFeatures)
	{
		this(corpus, indentSize);
		this.k = k;
		this.injectWSFeatures = injectWSFeatures;
		this.alignmentFeatures = alignmentFeatures;
	}

	public Formatter(Corpus corpus, int indentSize) {
		this.corpus = corpus;
//		k = (int)Math.sqrt(corpus.X.size());
		k = DEFAULT_K;
		this.indentSize = indentSize;
	}

	public String getOutput() {
		return output.toString();
	}

	public List<TokenPositionAnalysis> getAnalysisPerToken() {
		return analysis;
	}

	/** Format the document. Does not affect/alter doc. */
	public String format(InputDocument doc, boolean collectAnalysis) throws Exception {
		this.testDoc = InputDocument.dup(doc);
		output = new StringBuilder();
		this.realTokens = getRealTokens(testDoc.tokens);
		// make a complete copy of token stream and token objects
		this.originalTokens = new CodeBuffTokenStream(testDoc.tokens);
		// squeeze out ws and kill any line/col info so we can't use ground truth by mistake
		wipeCharPositionInfoAndWhitespaceTokens(testDoc.tokens); // all except for first token
		nlwsClassifier = new CodekNNClassifier(corpus, injectWSFeatures);
		alignClassifier = new CodekNNClassifier(corpus, alignmentFeatures);

		analysis = new Vector<>(testDoc.tokens.size());
		analysis.setSize(testDoc.tokens.size());

		if ( tokenToNodeMap == null ) {
			tokenToNodeMap = indexTree(testDoc.tree);
		}

		testDoc.tokens.seek(0);
		WritableToken firstToken = (WritableToken)testDoc.tokens.LT(1);
		String prefix = originalTokens.getText(Interval.of(0, firstToken.getTokenIndex())); // gets any comments in front + first real token
		charPosInLine = firstToken.getCharPositionInLine()+firstToken.getText().length()+1; // start where first token left off
		line = Tool.count(prefix, '\n') + 1;
		output.append(prefix);

		// first identify oversize lists with separators
		IdentifyOversizeLists splitter = new IdentifyOversizeLists(corpus, testDoc.tokens, tokenToNodeMap);
		ParseTreeWalker.DEFAULT.walk(splitter, testDoc.tree);
		tokenToListInfo = splitter.tokenToListInfo;

		realTokens = getRealTokens(testDoc.tokens);
		for (int i = Trainer.ANALYSIS_START_TOKEN_INDEX; i<realTokens.size(); i++) { // can't process first token
			int tokenIndexInStream = realTokens.get(i).getTokenIndex();
			processToken(i, tokenIndexInStream, collectAnalysis);
		}

		return output.toString();
	}

	public float getWSEditDistance() throws Exception {
		List<Token> wsTokens = filter(originalTokens.getTokens(),
		                              t -> t.getText().matches("\\s+")); // only count whitespace
		String originalWS = tokenText(wsTokens);

		String formattedOutput = getOutput();
		CommonTokenStream formatted_tokens = tokenize(formattedOutput, corpus.language.lexerClass);
		wsTokens = filter(formatted_tokens.getTokens(),
		                  t -> t.getText().matches("\\s+"));
		String formattedWS = tokenText(wsTokens);

		float editDistance = levenshteinDistance(originalWS, formattedWS);
		return editDistance;
	}

	public void processToken(int indexIntoRealTokens, int tokenIndexInStream, boolean collectAnalysis) {
		CommonToken curToken = (CommonToken)testDoc.tokens.get(tokenIndexInStream);
		String tokText = curToken.getText();
		TerminalNode node = tokenToNodeMap.get(curToken);

		emitCommentsToTheLeft(tokenIndexInStream);

		int[] features = getFeatures(testDoc, tokenIndexInStream);
		int[] featuresForAlign = new int[features.length];
		System.arraycopy(features, 0, featuresForAlign, 0, features.length);

		int injectNL_WS = nlwsClassifier.classify2(k, features, corpus.injectWhitespace,
		                                           Trainer.MAX_WS_CONTEXT_DIFF_THRESHOLD);

		int newlines = 0;
		int ws = 0;
		if ( (injectNL_WS&0xFF)==CAT_INJECT_NL ) {
			newlines = Trainer.unnlcat(injectNL_WS);
		}
		else if ( (injectNL_WS&0xFF)==CAT_INJECT_WS ) {
			ws = Trainer.unwscat(injectNL_WS);
		}

		if ( newlines==0 && ws==0 && cannotJoin(realTokens.get(indexIntoRealTokens-1), curToken) ) { // failsafe!
			ws = 1;
		}

		int alignOrIndent = CAT_NO_ALIGNMENT;

		if ( newlines>0 ) {
			output.append(Tool.newlines(newlines));
			line+=newlines;
			charPosInLine = 0;

			// getFeatures() doesn't know what line curToken is on. If \n, we need to find exemplars that start a line
			featuresForAlign[INDEX_FIRST_ON_LINE] = 1; // use \n prediction to match exemplars for alignment
			// if we decide to inject a newline, we better recompute this value before classifying alignment
			featuresForAlign[INDEX_MATCHING_TOKEN_DIFF_LINE] = getMatchingSymbolOnDiffLine(corpus, testDoc, node, line);

			alignOrIndent = alignClassifier.classify2(k, featuresForAlign, corpus.align, MAX_ALIGN_CONTEXT_DIFF_THRESHOLD);

			if ( (alignOrIndent&0xFF)==CAT_ALIGN_WITH_ANCESTOR_CHILD ) {
				align(alignOrIndent, node);
			}
			else if ( (alignOrIndent&0xFF)==CAT_INDENT_FROM_ANCESTOR_CHILD ) {
				indent(alignOrIndent, node);
			}
		}
		else {
			// inject whitespace instead of \n?
			output.append(Tool.spaces(ws));
			charPosInLine += ws;
		}

		// update Token object with position information now that we are about
		// to emit it.
		curToken.setLine(line);
		curToken.setCharPositionInLine(charPosInLine);

		TokenPositionAnalysis tokenPositionAnalysis =
			getTokenAnalysis(features, featuresForAlign, tokenIndexInStream, injectNL_WS, alignOrIndent, collectAnalysis);

		analysis.set(tokenIndexInStream, tokenPositionAnalysis);

		int n = tokText.length();
		tokenPositionAnalysis.charIndexStart = output.length();
		tokenPositionAnalysis.charIndexStop = tokenPositionAnalysis.charIndexStart + n - 1;

		// emit
		output.append(tokText);
		charPosInLine += n;
	}

	public void indent(int alignOrIndent, TerminalNode node) {
		int tokenIndexInStream = node.getSymbol().getTokenIndex();
		List<Token> tokensOnPreviousLine = getTokensOnPreviousLine(testDoc.tokens, tokenIndexInStream, line);
		Token firstTokenOnPrevLine = null;
		if ( tokensOnPreviousLine.size()>0 ) {
			firstTokenOnPrevLine = tokensOnPreviousLine.get(0);
		}

		if ( alignOrIndent==CAT_INDENT ) {
			if ( firstTokenOnPrevLine!=null ) { // if not on first line, we cannot indent
				int indentedCol = firstTokenOnPrevLine.getCharPositionInLine()+indentSize;
				charPosInLine = indentedCol;
				output.append(Tool.spaces(indentedCol));
			}
		}
		int[] deltaChild = Trainer.unindentcat(alignOrIndent);
		int deltaFromAncestor = deltaChild[0];
		int childIndex = deltaChild[1];
		ParserRuleContext earliestLeftAncestor = earliestAncestorStartingWithToken(node);
		ParserRuleContext ancestor = Trainer.getAncestor(earliestLeftAncestor, deltaFromAncestor);
		Token start = null;
		if ( ancestor==null ) {
			System.err.println("Whoops. No ancestor at that delta");
		}
		else {
			ParseTree child = ancestor.getChild(childIndex);
			if ( child instanceof ParserRuleContext ) {
				start = ((ParserRuleContext) child).getStart();
			}
			else if ( child instanceof TerminalNode ) {
				start = ((TerminalNode) child).getSymbol();
			}
			else {
				// uh oh.
				System.err.println("Whoops. Tried to access invalid child");
			}
		}
		if ( start!=null ) {
			int indentCol = start.getCharPositionInLine()+indentSize;
			charPosInLine = indentCol;
			output.append(Tool.spaces(indentCol));
		}
	}

	public void align(int alignOrIndent, TerminalNode node) {
		int[] deltaChild = Trainer.triple(alignOrIndent);
		int deltaFromAncestor = deltaChild[0];
		int childIndex = deltaChild[1];
		ParserRuleContext earliestLeftAncestor = earliestAncestorStartingWithToken(node);
		ParserRuleContext ancestor = Trainer.getAncestor(earliestLeftAncestor, deltaFromAncestor);
		Token start = null;
		if ( ancestor==null ) {
			System.err.println("Whoops. No ancestor at that delta");
		}
		else {
			ParseTree child = ancestor.getChild(childIndex);
			if (child instanceof ParserRuleContext) {
				start = ((ParserRuleContext) child).getStart();
			}
			else if (child instanceof TerminalNode) {
				start = ((TerminalNode) child).getSymbol();
			}
			else {
				// uh oh.
				System.err.println("Whoops. Tried to access invalid child");
			}
		}
		if ( start!=null ) {
			int indentCol = start.getCharPositionInLine();
			charPosInLine = indentCol;
			output.append(Tool.spaces(indentCol));
		}
	}

	public int[] getFeatures(InputDocument doc, int tokenIndexInStream) {
		doc.tokens.seek(tokenIndexInStream);
		boolean prevTokenStartsLine = false;
		if ( doc.tokens.index()-2 >= 0 ) {
			if ( doc.tokens.LT(-2)!=null ) {
				prevTokenStartsLine = doc.tokens.LT(-1).getLine()>doc.tokens.LT(-2).getLine();
			}
		}
		TerminalNode node = tokenToNodeMap.get(doc.tokens.get(tokenIndexInStream));
		if ( node==null ) {
			System.err.println("### No node associated with token "+doc.tokens.get(tokenIndexInStream));
			return null;
		}

		Token curToken = node.getSymbol();
		doc.tokens.seek(tokenIndexInStream); // seek so that LT(1) is tokens.get(i);
		Token prevToken = doc.tokens.LT(-1);

		int matchingSymbolOnDiffLine = getMatchingSymbolOnDiffLine(corpus, doc, node, line);

		boolean curTokenStartsNewLine = line>prevToken.getLine();

		int[] features = getContextFeatures(tokenToNodeMap, doc, tokenIndexInStream);

		setListInfoFeatures(tokenToListInfo, features, curToken);

		features[INDEX_PREV_FIRST_ON_LINE]       = prevTokenStartsLine ? 1 : 0;
		features[INDEX_MATCHING_TOKEN_DIFF_LINE] = matchingSymbolOnDiffLine;
		features[INDEX_FIRST_ON_LINE]            = curTokenStartsNewLine ? 1 : 0;

		return features;
	}

	/** Look into the token stream to get the comments to the left of current
	 *  token. Emit all whitespace and comments except for whitespace at the
	 *  end as we'll inject that per newline prediction.
	 *
	 *  This assumes we are grooming not totally reformatting.
	 *  We able to see original input stream for comment purposes. With all
	 *  whitespace removed, we can't emit this stuff properly at moment.
	 */
	public void emitCommentsToTheLeft(int tokenIndexInStream) {
		List<Token> hiddenTokensToLeft = originalTokens.getHiddenTokensToLeft(tokenIndexInStream);
		if ( hiddenTokensToLeft!=null ) {
			// if at least one is not whitespace, assume it's a comment and print all hidden stuff including whitespace
			boolean hasComment = Trainer.hasCommentToken(hiddenTokensToLeft);
			if ( hasComment ) {
				// avoid whitespace at end of sequence as we'll inject that
				int last = -1;
				for (int i=hiddenTokensToLeft.size()-1; i>=0; i--) {
					Token hidden = hiddenTokensToLeft.get(i);
					String hiddenText = hidden.getText();
					if ( !hiddenText.matches("\\s+") ) {
						last = i;
						break;
					}
				}
				List<Token> stripped = hiddenTokensToLeft.subList(0, last+1);
				for (Token hidden : stripped) {
					String hiddenText = hidden.getText();
					output.append(hiddenText);
					if ( hiddenText.matches("\\n+") ) {
						line += Tool.count(hiddenText, '\n');
						charPosInLine = 0;
					}
					else {
						// if a comment or plain ' ', must count char position
						charPosInLine += hiddenText.length();
					}
				}
			}
		}
	}

	public TokenPositionAnalysis getTokenAnalysis(int[] features, int[] featuresForAlign,
	                                              int tokenIndexInStream,
	                                              int injectNL_WS, int alignOrIndent,
	                                              boolean collectAnalysis)
	{
		CommonToken curToken = (CommonToken)testDoc.tokens.get(tokenIndexInStream);
		TerminalNode node = tokenToNodeMap.get(curToken);

		int actualWS = Trainer.getInjectWSCategory(originalTokens, tokenIndexInStream);
		String actualWSNL = getWSCategoryStr(actualWS);

		String wsDisplay = getWSCategoryStr(injectNL_WS);
		String alignDisplay = getAlignCategoryStr(alignOrIndent);
		String newlinePredictionString =
			String.format("### line %d: predicted %s actual %s",
			              curToken.getLine(), wsDisplay, actualWSNL);

		int actualAlignCategory = Trainer.getAlignmentCategory(originalTokens, node, indentSize);
		String actualAlignDisplay = getAlignCategoryStr(actualAlignCategory);

		String alignPredictionString =
			String.format("### line %d: predicted %s actual %s",
			              curToken.getLine(),
			              alignDisplay,
			              actualAlignDisplay);

		String newlineAnalysis = "";
		String alignAnalysis = "";
		if ( collectAnalysis ) { // this can be slow
			newlineAnalysis = newlinePredictionString+"\n"+
				nlwsClassifier.getPredictionAnalysis(testDoc, k, features, corpus.injectWhitespace,
				                                     MAX_WS_CONTEXT_DIFF_THRESHOLD);
			if ( (injectNL_WS&0xFF)==CAT_INJECT_NL ) {
				alignAnalysis =
					alignPredictionString+"\n"+
						alignClassifier.getPredictionAnalysis(testDoc, k, featuresForAlign, corpus.align,
						                                      MAX_ALIGN_CONTEXT_DIFF_THRESHOLD);
			}
		}
		TokenPositionAnalysis a = new TokenPositionAnalysis(curToken, injectNL_WS, newlineAnalysis, alignOrIndent, alignAnalysis);
		a.actualWS = Trainer.getInjectWSCategory(originalTokens, tokenIndexInStream);
		a.actualAlign = actualAlignCategory;
		return a;
	}

	public static String getWSCategoryStr(int injectNL_WS) {
		int[] elements = Trainer.triple(injectNL_WS);
		int cat = injectNL_WS&0xFF;
		String catS = "none";
		if ( cat==CAT_INJECT_NL ) catS = "'\\n'";
		else if ( cat==CAT_INJECT_WS ) catS = "' '";
		return String.format("%s|%d|%d", catS, elements[0], elements[1]);
	}

	public static String getAlignCategoryStr(int alignOrIndent) {
		int[] elements = Trainer.triple(alignOrIndent);
		int cat = alignOrIndent&0xFF;
		String catS = "none";
		if ( cat==CAT_ALIGN_WITH_ANCESTOR_CHILD ) catS = "align^";
		else if ( cat==CAT_INDENT_FROM_ANCESTOR_CHILD ) catS = "indent^";
		else if ( cat==CAT_INDENT ) catS = "indent";
		return String.format("%s|%d|%d", catS, elements[0], elements[1]);
	}

	/** Do not join two words like "finaldouble" or numbers like "3double",
	 *  "double3", "34", (3 and 4 are different tokens) etc...
	 */
	public static boolean cannotJoin(Token prevToken, Token curToken) {
		String prevTokenText = prevToken.getText();
		char prevLastChar = prevTokenText.charAt(prevTokenText.length()-1);
		String curTokenText = curToken.getText();
		char curFirstChar = curTokenText.charAt(0);
		return Character.isLetterOrDigit(prevLastChar) && Character.isLetterOrDigit(curFirstChar);
	}

	public static void wipeCharPositionInfoAndWhitespaceTokens(CommonTokenStream tokens) {
		tokens.fill();
		CommonToken dummy = new CommonToken(Token.INVALID_TYPE, "");
		dummy.setChannel(Token.HIDDEN_CHANNEL);
		tokens.seek(0);
		Token firstRealToken = tokens.LT(1);
		for (int i = 0; i<tokens.size(); i++) {
			if ( i==firstRealToken.getTokenIndex() ) continue; // don't wack first token
			CommonToken t = (CommonToken)tokens.get(i);
			if ( t.getText().matches("\\s+") ) {
				tokens.getTokens().set(i, dummy); // wack whitespace token so we can't use it during prediction
			}
			else {
				t.setLine(0);
				t.setCharPositionInLine(-1);
			}
		}
	}

}
