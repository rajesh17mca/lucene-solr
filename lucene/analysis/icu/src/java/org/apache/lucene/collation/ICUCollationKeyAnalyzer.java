package org.apache.lucene.collation;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import com.ibm.icu.text.Collator;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordTokenizer;
import org.apache.lucene.collation.CollationKeyAnalyzer; // javadocs
import org.apache.lucene.util.Version;

import java.io.Reader;

/**
 * <p>
 *   Configures {@link KeywordTokenizer} with {@link ICUCollationAttributeFactory}.
 * <p>
 *   Converts the token into its {@link com.ibm.icu.text.CollationKey}, and
 *   then encodes the CollationKey directly to allow it to
 *   be stored as an index term.
 * </p>
 * <p>
 *   <strong>WARNING:</strong> Make sure you use exactly the same Collator at
 *   index and query time -- CollationKeys are only comparable when produced by
 *   the same Collator.  {@link com.ibm.icu.text.RuleBasedCollator}s are 
 *   independently versioned, so it is safe to search against stored
 *   CollationKeys if the following are exactly the same (best practice is
 *   to store this information with the index and check that they remain the
 *   same at query time):
 * </p>
 * <ol>
 *   <li>
 *     Collator version - see {@link Collator#getVersion()}
 *   </li>
 *   <li>
 *     The collation strength used - see {@link Collator#setStrength(int)}
 *   </li>
 * </ol> 
 * <p>
 *   CollationKeys generated by ICU Collators are not compatible with those
 *   generated by java.text.Collators.  Specifically, if you use 
 *   ICUCollationKeyAnalyzer to generate index terms, do not use 
 *   {@link CollationKeyAnalyzer} on the query side, or vice versa.
 * </p>
 * <p>
 *   ICUCollationKeyAnalyzer is significantly faster and generates significantly
 *   shorter keys than CollationKeyAnalyzer.  See
 *   <a href="http://site.icu-project.org/charts/collation-icu4j-sun"
 *   >http://site.icu-project.org/charts/collation-icu4j-sun</a> for key
 *   generation timing and key length comparisons between ICU4J and
 *   java.text.Collator over several languages.
 * </p>
 */
public final class ICUCollationKeyAnalyzer extends Analyzer {
  private final ICUCollationAttributeFactory factory;

  /**
   * Create a new ICUCollationKeyAnalyzer, using the specified collator.
   *
   * @param collator CollationKey generator
   */
  public ICUCollationKeyAnalyzer(Collator collator) {
    this.factory = new ICUCollationAttributeFactory(collator);
  }

  /**
   * @deprecated Use {@link #ICUCollationKeyAnalyzer(Collator)}
   */
  @Deprecated
  public ICUCollationKeyAnalyzer(Version matchVersion, Collator collator) {
    this.factory = new ICUCollationAttributeFactory(collator);
  }


  @Override
  protected TokenStreamComponents createComponents(String fieldName) {
    KeywordTokenizer tokenizer = new KeywordTokenizer(factory, KeywordTokenizer.DEFAULT_BUFFER_SIZE);
    return new TokenStreamComponents(tokenizer, tokenizer);
  }
}
