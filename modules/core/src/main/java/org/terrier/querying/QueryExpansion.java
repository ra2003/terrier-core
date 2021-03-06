/*
 * Terrier - Terabyte Retriever
 * Webpage: http://terrier.org
 * Contact: terrier{a.}dcs.gla.ac.uk
 * University of Glasgow - School of Computing Science
 * http://www.gla.ac.uk/
 *
 * The contents of this file are subject to the Mozilla Public License
 * Version 1.1 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
 * the License for the specific language governing rights and limitations
 * under the License.
 *
 * The Original Code is QueryExpansion.java.
 *
 * The Original Code is Copyright (C) 2004-2020 the University of Glasgow.
 * All Rights Reserved.
 *
 * Contributor(s):
 *   Gianni Amatti <gba{a.}fub.it> (original author)
 *   Ben He <ben{a.}dcs.gla.ac.uk>
 *   Vassilis Plachouras <vassilis{a.}dcs.gla.ac.uk>
 *   Craig Macdonald <craigm{a.}dcs.gla.ac.uk>
 */
package org.terrier.querying;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terrier.matching.MatchingQueryTerms;
import org.terrier.matching.models.queryexpansion.Bo1;
import org.terrier.matching.models.queryexpansion.QueryExpansionModel;
import org.terrier.querying.parser.SingleTermQuery;
import org.terrier.structures.CollectionStatistics;
import org.terrier.structures.DocumentIndex;
import org.terrier.structures.Index;
import org.terrier.structures.IndexFactory;
import org.terrier.structures.Lexicon;
import org.terrier.structures.MetaIndex;
import org.terrier.structures.PostingIndex;
import org.terrier.utility.ApplicationSetup;
import org.terrier.utility.Rounding;
/**
 * Implements automatic query expansion as PostProcess that is applied to the result set
 * after 1st-time matching.
 * <B>Controls</B>
 * <ul>
 * <li><tt>qemodel</tt> : The query expansion model used for Query Expansion. Defaults to Bo1.</li>
 * <li><tt>qe_fb_terms</tt> : The maximum number of most weighted terms in the 
 * pseudo relevance set to be added to the original query. The system performs a conservative
 * query expansion if this property is set to 0. A conservative query expansion only re-weighs
 * the original query terms without adding new terms to the query. Default set by property <tt>expansion.terms</tt>.</li>
 * <li><tt>qe_fb_docs</tt> : The number of top documents from the 1st pass 
 * retrieval to use for QE. The query is expanded from this set of documents, also 
 * known as the pseudo relevance set. Default set by property <tt>expansion.docs</tt>.</li>
 * </ul>
 * <B>Properties</B>
 * <li><tt>qe.feedback.selector</tt> : The class to be used for selecting feedback documents.</li>
 * <li><tt>qe.expansion.terms.class</tt> : The class to be used for selecting expansion terms from feedback documents.</li>
 * </ul>
  * @author Gianni Amatti, Ben He, Vassilis Plachouras, Craig Macdonald
 */
@ProcessPhaseRequisites({ManagerRequisite.MQT, ManagerRequisite.RESULTSET})
public class QueryExpansion implements MQTRewritingProcess {
	protected static final Logger logger = LoggerFactory.getLogger(QueryExpansion.class);


	public static class QueryExpansionConfig 
	{
		public static final String CONTROL_EXP_DOCS = "qe_fb_docs";
		public static final String CONTROL_EXP_TERMS = "qe_fb_terms";
		
		public final int EXPANSION_DOCUMENTS;
		public final int EXPANSION_TERMS;

		private QueryExpansionConfig(int docs, int terms) {
			EXPANSION_DOCUMENTS = docs;
			EXPANSION_TERMS = terms;
		}
	}

	/** Get the standard configuration for QueryExpansion, namely number of terms and docs, 
	 * from the Request, using two controls.
	 */
	public static QueryExpansionConfig configFromRequest(Request r) {
		int docs = ApplicationSetup.EXPANSION_DOCUMENTS;
		if (r.getControl(QueryExpansionConfig.CONTROL_EXP_DOCS, null) != null)
			docs = Integer.parseInt(r.getControl(QueryExpansionConfig.CONTROL_EXP_DOCS));
		int terms = ApplicationSetup.EXPANSION_TERMS;
		if (r.getControl(QueryExpansionConfig.CONTROL_EXP_TERMS, null) != null)
			terms = Integer.parseInt(r.getControl(QueryExpansionConfig.CONTROL_EXP_TERMS));
		return new QueryExpansionConfig(docs, terms);
	}
	
	/**
	 * The default namespace of query expansion model classes.
	 * The query expansion model names are prefixed with this
	 * namespace, if they are not fully qualified.
	 */
	public static final String NAMESPACE_QEMODEL = "org.terrier.matching.models.queryexpansion.";
	/**
	 * Caching the query expansion models that have been
	 * created so far.
	 */
	protected Map<String, QueryExpansionModel> Cache_QueryExpansionModel = new HashMap<String, QueryExpansionModel>();
	/** The document index used for retrieval. */
	protected DocumentIndex documentIndex;
	protected MetaIndex metaIndex;
	/** The inverted index used for retrieval. */
	protected PostingIndex<?> invertedIndex;
	/** An instance of Lexicon class. */
	protected Lexicon<String> lexicon;
	/** The direct index used for retrieval. */
	protected PostingIndex<?> directIndex;

	/** Set to true if this instancae has warned about the control "qemodel" not being set */
	protected boolean warnQeModel = false;
	
	protected String expanderNames[] = ApplicationSetup.getProperty("qe.expansion.terms.class", "DFRBagExpansionTerms").split("\\s*,\\s*");
	
	/** The statistics of the index */
	protected CollectionStatistics collStats;
	/** The query expansion model used. */
	protected QueryExpansionModel QEModel = new Bo1();
	/** The process by which to select feedback documents */
	protected FeedbackSelector selector = null;
	/**
	* The default constructor of QueryExpansion.
	*/
	public QueryExpansion() {}
	
	public QueryExpansion(String[] expansionTerms) {
		if (expansionTerms.length > 0)
		{
			expanderNames[0] = expansionTerms[0];
		}
		if (expansionTerms.length > 1)
		{
			this.QEModel = getQueryExpansionModel(expansionTerms[1]);
		}
	}
	
	/** Set the used query expansion model.
	*  @param _QEModel QueryExpansionModel The query expansion model to be used.
	*/
	public void setQueryExpansionModel(QueryExpansionModel _QEModel){
		this.QEModel = _QEModel;
	}
	/**
 	* This method implements the functionality of expanding a query.
 	* @param query MatchingQueryTerms the query terms of 
 	*		the original query.
 	* @param rq the Request thus far, giving access to the query and the result set
 	*/
	@Override
	public boolean expandQuery(MatchingQueryTerms query, Request rq) throws IOException 
	{
		QueryExpansionConfig qeConfig = configFromRequest(rq);
		//get the query expansion model to use
		String qeModel = rq.getControl("qemodel");
		if (qeModel == null || qeModel.length() ==0)
		{
			if (! warnQeModel)
				logger.warn("qemodel control not set for QueryExpansion"+
					" post process. Using default model Bo1");
			warnQeModel = true;
			qeModel = "Bo1";
		}
		setQueryExpansionModel(getQueryExpansionModel(qeModel));
		if(logger.isDebugEnabled()){
			logger.debug("query expansion model: " + QEModel.getInfo());
		}
		
		// the number of term to re-weight (i.e. to do relevance feedback) is
		// the maximum between the system setting and the actual query length.
		// if the query length is larger than the system setting, it does not
		// make sense to do relevance feedback for a portion of the query. Therefore, 
		// we re-weight the number of query length of terms.
		int numberOfTermsToReweight = Math.max(qeConfig.EXPANSION_TERMS, query.size());
		if (qeConfig.EXPANSION_TERMS == 0)
			numberOfTermsToReweight = 0;

		if (selector == null)
			selector = this.getFeedbackSelector(rq);
		if (selector == null)
			return false;
		FeedbackDocument[] feedback = selector.getFeedbackDocuments(rq);
		if (feedback == null || feedback.length == 0)
			return false;
	
		ExpansionTerms expansionTerms = getExpansionTerms();
		expansionTerms.setModel(QEModel);
		
		for(FeedbackDocument doc : feedback)
		{
			expansionTerms.insertDocument(doc);
		}
		logger.debug("Selecting "+numberOfTermsToReweight + " from " + expansionTerms.getNumberOfUniqueTerms());
		
		expansionTerms.setOriginalQueryTerms(query);
		SingleTermQuery[] expandedTerms = expansionTerms.getExpandedTerms(numberOfTermsToReweight);
		for (int i = 0; i < expandedTerms.length; i++){
			SingleTermQuery expandedTerm = expandedTerms[i];
			query.addTermPropertyWeight(expandedTerm.getTerm(), expandedTerm.getWeight());
			if(logger.isDebugEnabled()){
				logger.debug("term " + expandedTerms[i].getTerm()
				 	+ " appears in expanded query with normalised weight: "
					+ Rounding.toString(query.getTermWeight(expandedTerms[i].getTerm()), 4));
			}
		}
		return true;
	}

	/** For easier sub-classing of which index the query expansion comes from */
	protected Index getIndex(Manager m)
	{
		if (! IndexFactory.isLocal(m.getIndexRef()))
		{
			throw new IllegalStateException("QueryExpansion needs a local index");
		}
		return IndexFactory.of(m.getIndexRef());
	}
	
	/** load the expansion terms, as per the property <tt>qe.expansion.terms.class</tt>. Defaults to DFRBagExpansionTerms.
	 * @return an ExpansionTerms instance, which may or may not wrap other ExpansionTerms instances
	 */
	protected ExpansionTerms getExpansionTerms()
	{
		
		ExpansionTerms rtr = null;
		
		//foreach name, starting from the last, finishing with the first
		
		for(int i=expanderNames.length -1;i>=0;i--)
		{
			String expanderName = expanderNames[i];
			ExpansionTerms next = null;
			if (! expanderName.contains("."))
				expanderName = "org.terrier.querying."+expanderName;
			
			try{
				Class<? extends ExpansionTerms> clz = ApplicationSetup.getClass(expanderName).asSubclass(ExpansionTerms.class);
				if (expanderNames.length -1 == i)
				{
					next = clz
						.getConstructor(CollectionStatistics.class, Lexicon.class, PostingIndex.class, DocumentIndex.class)
						.newInstance(collStats,lexicon, directIndex, documentIndex);
				}
				else
				{
					next = clz.getConstructor(ExpansionTerms.class).newInstance(rtr);
				}
				rtr = next;
			}
			catch (Exception e) {
				logger.error("Error during GetExpansionTerms", e);
				return null;
			}
		}
		return rtr;
	}


	/** load the feedback selector, based on the property <tt>qe.feedback.selector</tt>  */
	protected FeedbackSelector getFeedbackSelector(Request rq)
	{
		String[] names = ApplicationSetup.getProperty("qe.feedback.selector", "PseudoRelevanceFeedbackSelector").split("\\s*,\\s*");
		FeedbackSelector rtr = null;
		for(int i=names.length -1;i>=0;i--)
		{
			String name = names[i];
			if (! name.contains("."))
				name = "org.terrier.querying."+name;
			
			FeedbackSelector next = null;
			try{
				Class<? extends FeedbackSelector> nextClass = ApplicationSetup.getClass(name).asSubclass(FeedbackSelector.class);
				if (names.length -1 == i)
				{
					next = nextClass.newInstance();
				}
				else
				{
					next = nextClass.getConstructor(FeedbackSelector.class).newInstance(rtr);
				}
		
				rtr = next;
			} catch (Exception e) { 
				logger.error("Problem loading a FeedbackSelector called "+ name, e);
				return null;
			}
			rtr.setIndex(lastIndex);//TODO index should come from Request
		}
		return rtr;	
	}
	
	protected Index lastIndex = null; //TODO remove
	public String lastExpandedQuery;
	/**
	 * Runs the actual query expansion
	 * @see org.terrier.querying.PostProcess#process(org.terrier.querying.Manager,org.terrier.querying.SearchRequest)
	 */
	public void process(Manager manager, Request q) {
	   	Index index = getIndex(manager);
		configureIndex(index);
		if (directIndex == null)
		{
			logger.error("This index does not have a direct index. Query expansion disabled!!");
			return;
		}
		logger.debug("Starting query expansion post-processing.");
		
		MatchingQueryTerms queryTerms = q.getMatchingQueryTerms();
		if (queryTerms == null)
		{
			logger.warn("No query terms for this query. Skipping QE");
			return;
		}
		// get the expanded query terms
		try{
			expandQuery(queryTerms, q);
		} catch (IOException ioe) {
			logger.error("IOException while expanding query, skipping QE", ioe);
			return;
		}
		if(logger.isDebugEnabled()){
			logger.debug("query length after expansion: " + queryTerms.size());
			logger.debug("Expanded query: ");
		}
		final String[] newQueryTerms = queryTerms.getTerms();
		StringBuilder newQuery = new StringBuilder();
		for (int i = 0; i < newQueryTerms.length; i++){
			try{
				if(logger.isDebugEnabled()){
					logger.debug((i + 1) + ": " + newQueryTerms[i] +
						", normalisedFrequency: " + Rounding.toString(queryTerms.getTermWeight(newQueryTerms[i]), 4));
				}
				newQuery.append(newQueryTerms[i]);
				newQuery.append('^');
				newQuery.append(Rounding.toString(queryTerms.getTermWeight(newQueryTerms[i]), 9));
				newQuery.append(' ');
			}
			catch(NullPointerException npe){
				logger.error("Nullpointer exception occured in Query Expansion dumping of new Query", npe);
			}
		}
		
		logger.info("NEWQUERY "+q.getQueryID() +" "+newQuery.toString());
		lastExpandedQuery = newQuery.toString();
		q.setControl("QE.ExpandedQuery", newQuery.toString());
		final boolean no2ndPass = Boolean.parseBoolean(ApplicationSetup.getProperty("qe.no.2nd.matching", "false"));
		if (no2ndPass)
		{
			return;
		}
		
		//run retrieval process again for the expanded query
		logger.info("Accessing inverted file for expanded query " + q.getQueryID());
		//THIS ASSUMES THAT QueryExpansion directly follows Matching
		((LocalManager)manager).processModuleManager.getModule(q.getControl("previousprocess")).process(manager, q);
	}
	
	@Override
	public void configureIndex(Index index) {
		lastIndex = index;
		documentIndex = index.getDocumentIndex();
		invertedIndex = index.getInvertedIndex();
		lexicon = index.getLexicon();
		collStats = index.getCollectionStatistics(); 
		directIndex = index.getDirectIndex();
		metaIndex = index.getMetaIndex();
	}
	/** Obtain the query expansion model for QE to use.
	 *  This will be cached in a hashtable for the lifetime of the
	 *  application. If Name does not contain ".", then <tt>
	 *  NAMESPACE_QEMODEL </tt> will be prefixed to it before loading.
	 *  @param Name the name of the query expansion model to load.
	 */
	public QueryExpansionModel getQueryExpansionModel(String Name)
	{
		QueryExpansionModel rtr = null;
		if (Name.indexOf(".") < 0 )
			Name = NAMESPACE_QEMODEL +Name;
		//check for acceptable matching models
		rtr = (QueryExpansionModel)Cache_QueryExpansionModel.get(Name);
		if (rtr == null)
		{
			try
			{
				rtr = (QueryExpansionModel) ApplicationSetup.getClass(Name).newInstance();
			}
			catch(Exception e)
			{
				logger.error("Problem with qemodel named: "+Name+" : ",e);
				return null;
			}
			Cache_QueryExpansionModel.put(Name, rtr);
		}
		return rtr;
	}
	
	/**
	 * Returns the name of the used query expansion model.
	 * @return String the name of the used query expansion model.
	 */
	public String getInfo() {
		if (QEModel != null)
			return QEModel.getInfo();
		return "";
	}
}
