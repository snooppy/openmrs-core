/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.api.db.hibernate.search;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.queryParser.QueryParser.Operator;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermsFilter;
import org.apache.lucene.util.Version;
import org.hibernate.Session;
import org.hibernate.search.FullTextQuery;
import org.hibernate.search.FullTextSession;
import org.hibernate.search.Search;
import org.hibernate.search.query.dsl.QueryBuilder;
import org.openmrs.collection.ListPart;

/**
 * Performs Lucene queries.
 * 
 * @since 1.11
 */
public abstract class LuceneQuery<T> extends SearchQuery<T> {
	
	private FullTextQuery fullTextQuery;
	
	private Set<Set<Term>> includeTerms = new HashSet<Set<Term>>();
	
	private Set<Term> excludeTerms = new HashSet<Term>();
	
	/**
	 * The preferred way to create a Lucene query using the query parser.
	 * 
	 * @param query
	 * @param session
	 * @param type
	 * @return the Lucene query
	 */
	public static <T> LuceneQuery<T> newQuery(final String query, final Session session, final Class<T> type) {
		return new LuceneQuery<T>(
		                          session, type) {
			
			@Override
			protected Query prepareQuery() throws ParseException {
				if (query.isEmpty()) {
					return new MatchAllDocsQuery();
				}
				return newQueryParser().parse(query);
			}
			
		};
	}
	
	/**
	 * Escape any characters that can be interpreted by the query parser.
	 * 
	 * @param query
	 * @return the escaped query
	 */
	public static String escapeQuery(final String query) {
		return QueryParser.escape(query);
	}
	
	public LuceneQuery(Session session, Class<T> type) {
		super(session, type);
		
		buildQuery();
	}
	
	/**
	 * Include items with the given value in the specified field.
	 * 
	 * @param field
	 * @param value
	 * @return the query
	 */
	public LuceneQuery<T> include(String field, Object value) {
		if (value != null) {
			include(field, new Object[] { value });
		}
		
		return this;
	}
	
	/**
	 * Include items with any of the given values in the specified field.
	 * 
	 * @param field
	 * @param values
	 * @return the query
	 */
	public LuceneQuery<T> include(String field, Object[] values) {
		if (values != null && values.length != 0) {
			Set<Term> terms = new HashSet<Term>();
			for (Object value : values) {
				terms.add(new Term(field, value.toString()));
			}
			includeTerms.add(terms);
			
			fullTextQuery.enableFullTextFilter("termsFilterFactory").setParameter("includeTerms", includeTerms)
			        .setParameter("excludeTerms", excludeTerms);
		}
		
		return this;
	}
	
	/**
	 * Exclude any items with the given value in the specified field.
	 * 
	 * @param field
	 * @param value
	 * @return the query
	 */
	public LuceneQuery<T> exclude(String field, Object value) {
		if (value != null) {
			exclude(field, new Object[] { value });
		}
		
		return this;
	}
	
	/**
	 * Exclude any items with the given values in the specified field.
	 * 
	 * @param field
	 * @param values
	 * @return the query
	 */
	public LuceneQuery<T> exclude(String field, Object[] values) {
		if (values != null && values.length != 0) {
			for (Object value : values) {
				excludeTerms.add(new Term(field, value.toString()));
			}
			
			fullTextQuery.enableFullTextFilter("termsFilterFactory").setParameter("includeTerms", includeTerms)
			        .setParameter("excludeTerms", excludeTerms);
		}
		
		return this;
	}
	
	/**
	 * It is called by the constructor to get an instance of a query.
	 * <p>
	 * To construct the query you can use {@link #newQueryBuilder()} or {@link #newQueryParser()},
	 * which are created for the proper type.
	 * 
	 * @return the query
	 * @throws ParseException
	 */
	protected abstract Query prepareQuery() throws ParseException;
	
	/**
	 * It is called by the constructor after creating {@link FullTextQuery}.
	 * <p>
	 * You can override it to adjust the full text query, e.g. add a filter.
	 * 
	 * @param fullTextQuery
	 */
	protected void adjustFullTextQuery(FullTextQuery fullTextQuery) {
	}
	
	/**
	 * You can use it in {@link #prepareQuery()}.
	 * 
	 * @return the query builder
	 */
	protected QueryBuilder newQueryBuilder() {
		return getFullTextSession().getSearchFactory().buildQueryBuilder().forEntity(getType()).get();
	}
	
	/**
	 * You can use it in {@link #prepareQuery()}.
	 * 
	 * @return the query parser
	 */
	protected QueryParser newQueryParser() {
		Analyzer analyzer = getFullTextSession().getSearchFactory().getAnalyzer(getType());
		QueryParser queryParser = new QueryParser(Version.LUCENE_31, null, analyzer);
		queryParser.setDefaultOperator(Operator.AND);
		return queryParser;
	}
	
	/**
	 * Gives you access to the full text session.
	 * 
	 * @return the full text session
	 */
	protected FullTextSession getFullTextSession() {
		return Search.getFullTextSession(getSession());
	}
	
	/**
	 * Skip elements, values of which repeat in the given field.
	 * <p>
	 * Only the first element will be included in the results.
	 * <p>
	 * <b>Note:</b> For performance reasons you should call this method as last when constructing a
	 * query. When called it will project the query and create a filter to eliminate duplicates.
	 * 
	 * @param field
	 * @return
	 */
	public LuceneQuery<T> skipSame(String field) {
		String idPropertyName = getSession().getSessionFactory().getClassMetadata(getType()).getIdentifierPropertyName();
		
		List<Object> documents = listProjection(idPropertyName, field);
		
		Set<Object> uniqueFieldValues = new HashSet<Object>();
		TermsFilter termsFilter = new TermsFilter();
		for (Object document : documents) {
			Object[] row = (Object[]) document;
			if (uniqueFieldValues.add(row[1])) {
				termsFilter.addTerm(new Term(idPropertyName, row[0].toString()));
			}
		}
		
		buildQuery();
		fullTextQuery.setFilter(termsFilter);
		
		return this;
	}
	
	@Override
	public T uniqueResult() {
		@SuppressWarnings("unchecked")
		T result = (T) fullTextQuery.uniqueResult();
		
		return result;
	}
	
	@Override
	public List<T> list() {
		@SuppressWarnings("unchecked")
		List<T> list = fullTextQuery.list();
		
		return list;
	}
	
	@Override
	public ListPart<T> listPart(Long firstResult, Long maxResults) {
		applyPartialResults(fullTextQuery, firstResult, maxResults);
		
		@SuppressWarnings("unchecked")
		List<T> list = fullTextQuery.list();
		
		return ListPart.newListPart(list, firstResult, maxResults, Long.valueOf(fullTextQuery.getResultSize()),
		    !fullTextQuery.hasPartialResults());
	}
	
	/**
	 * @see org.openmrs.api.db.hibernate.search.SearchQuery#resultSize()
	 */
	@Override
	public long resultSize() {
		return fullTextQuery.getResultSize();
	}
	
	public List<Object> listProjection(String... fields) {
		fullTextQuery.setProjection(fields);
		
		@SuppressWarnings("unchecked")
		List<Object> list = fullTextQuery.list();
		
		return list;
	}
	
	public ListPart<Object> listPartProjection(Long firstResult, Long maxResults, String... fields) {
		applyPartialResults(fullTextQuery, firstResult, maxResults);
		
		fullTextQuery.setProjection(fields);
		
		@SuppressWarnings("unchecked")
		List<Object> list = fullTextQuery.list();
		
		return ListPart.newListPart(list, firstResult, maxResults, Long.valueOf(fullTextQuery.getResultSize()),
		    !fullTextQuery.hasPartialResults());
		
	}
	
	public ListPart<Object> listPartProjection(Integer firstResult, Integer maxResults, String... fields) {
		Long first = (firstResult != null) ? Long.valueOf(firstResult) : null;
		Long max = (maxResults != null) ? Long.valueOf(maxResults) : null;
		return listPartProjection(first, max, fields);
	}
	
	private void buildQuery() {
		Query query;
		try {
			query = prepareQuery();
		}
		catch (ParseException e) {
			throw new IllegalStateException("Invalid query", e);
		}
		
		fullTextQuery = getFullTextSession().createFullTextQuery(query, getType());
		adjustFullTextQuery(fullTextQuery);
	}
	
	private void applyPartialResults(FullTextQuery fullTextQuery, Long firstResult, Long maxResults) {
		if (firstResult != null) {
			fullTextQuery.setFirstResult(firstResult.intValue());
		}
		
		if (maxResults != null) {
			fullTextQuery.setMaxResults(maxResults.intValue());
		}
	}
}
