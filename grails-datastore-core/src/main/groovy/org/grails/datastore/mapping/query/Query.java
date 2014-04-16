/* Copyright (C) 2010 SpringSource
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.datastore.mapping.query;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.FetchType;
import javax.persistence.FlushModeType;

import org.grails.datastore.mapping.core.AbstractDatastore;
import org.grails.datastore.mapping.core.ConnectionNotFoundException;
import org.grails.datastore.mapping.core.Session;
import org.grails.datastore.mapping.engine.EntityAccess;
import org.grails.datastore.mapping.engine.EntityPersister;
import org.grails.datastore.mapping.model.MappingContext;
import org.grails.datastore.mapping.model.PersistentEntity;
import org.grails.datastore.mapping.model.PersistentProperty;
import org.grails.datastore.mapping.model.types.Association;
import org.grails.datastore.mapping.query.api.AssociationCriteria;
import org.grails.datastore.mapping.query.api.QueryableCriteria;
import org.grails.datastore.mapping.query.event.PostQueryEvent;
import org.grails.datastore.mapping.query.event.PreQueryEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.util.Assert;

/**
 * Models a query that can be executed against a data store.
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class Query implements Cloneable{

    protected PersistentEntity entity;
    protected Junction criteria = new Conjunction();
    protected ProjectionList projections = new ProjectionList();
    protected int max = -1;
    protected int offset = 0;
    protected List<Order> orderBy = new ArrayList<Order>();
    protected Session session;
    protected boolean uniqueResult;
    protected Map<String, FetchType> fetchStrategies = new HashMap<String,FetchType>();
    protected boolean queryCache;

    protected Query(Session session, PersistentEntity entity) {
        this.entity = entity;
        this.session = session;
    }

    @Override
    public Object clone() {
        Query newQuery = getSession().createQuery(entity.getJavaClass());
        for (Criterion criterion : criteria.getCriteria()) {
            newQuery.add(criterion);
        }
        return newQuery;
    }

    /**
     * @return The criteria defined by this query
     */
    public Junction getCriteria() {
        return criteria;
    }

    /**
     * Specifies whether a join query should be used (if join queries are supported by the underlying datastore)
     *
     * @param property The property
     * @return The query
     */
    public Query join(String property) {
        fetchStrategies.put(property, FetchType.EAGER);
        return this;
    }

    /**
     * Specifies whether a select (lazy) query should be used (if join queries are supported by the underlying datastore)
     *
     * @param property The property
     * @return The query
     */
    public Query select(String property) {
        fetchStrategies.put(property, FetchType.LAZY);
        return this;
    }

    /**
     * Specifies whether the query results should be cached (if supported by the underlying datastore)
     *
     * @param cache True if caching should be enabled
     * @return The query
     */
    public Query cache(boolean cache) {
        queryCache = true;
        return this;
    }

    public ProjectionList projections() {
        return projections;
    }

    /**
     * Adds the specified criterion instance to the query
     *
     * @param criterion The criterion instance
     */
    public void add(Criterion criterion) {
        Junction currentJunction = criteria;
        addToJunction(currentJunction, criterion);
    }

    /**
     * @return The session that created the query
     */
    public Session getSession() {
        return session;
    }

    /**
     * @return The PersistentEntity being query
     */
    public PersistentEntity getEntity() {
        return entity;
    }

    /**
     * Creates a disjunction (OR) query
     * @return The Junction instance
     */
    public Junction disjunction() {
        Junction currentJunction = criteria;
        return disjunction(currentJunction);
    }

    /**
     * Creates a disjunction (OR) query
     * @return The Junction instance
     */
    public Junction conjunction() {
        Junction currentJunction = criteria;
        return conjunction(currentJunction);
    }

    /**
     * Creates a negation of several criterion
     * @return The negation
     */
    public Junction negation() {
        Junction currentJunction = criteria;
        return negation(currentJunction);
    }

    private Junction negation(Junction currentJunction) {
        Negation dis = new Negation();
        currentJunction.add(dis);
        return dis;
    }

    /**
     * Defines the maximum number of results to return
     * @param max The max results
     * @return This query instance
     */
    public Query max(int max) {
        this.max = max;
        return this;
    }

    /**
     * Defines the maximum number of results to return
     * @param max The max results
     * @return This query instance
     */
    public Query maxResults(int max) {
        return max(max);
    }

    /**
     * Defines the offset (the first result index) of the query
     * @param offset The offset
     * @return This query instance
     */
    public Query offset(int offset) {
        this.offset = offset;
        return this;
    }

    /**
     * Defines the offset (the first result index) of the query
     * @param offset The offset
     * @return This query instance
     */
    public Query firstResult(int offset) {
        return offset(offset);
    }

    /**
     * Specifies the order of results
     * @param order The order object
     * @return The Query instance
     */
    public Query order(Order order) {
        if (order != null) {
            orderBy.add(order);
        }
        return this;
    }

    /**
     * Gets the Order entries for this query
     * @return The order entries
     */
    public List<Order> getOrderBy() {
        return orderBy;
    }

    /**
     * Restricts the results by the given properties value
     *
     * @param property The name of the property
     * @param value The value to restrict by
     * @return This query instance
     */
    public Query eq(String property, Object value) {
        Object resolved = resolveIdIfEntity(value);
        if (resolved == value) {
           criteria.add(Restrictions.eq(property, value));
        }
        else {
           criteria.add(Restrictions.eq(property,resolved));
        }
        return this;
    }

    /**
     * Shortcut to restrict the query to multiple given property values
     *
     * @param values The values
     * @return This query instance
     */
    public Query allEq(Map<String, Object> values) {
        Junction conjunction = conjunction();
        for (String property : values.keySet()) {
            conjunction.add( Restrictions.eq(property, values.get(property)));
        }
        return this;
    }

    /**
     * Used to restrict a value to be empty (such as a blank string or an empty collection)
     *
     * @param property The property name
    */
    public Query isEmpty(String property) {
        criteria.add(Restrictions.isEmpty(property));
        return this;
    }

    /**
     * Used to restrict a value to be not empty (such as a blank string or an empty collection)
     *
     * @param property The property name
    */
    public Query isNotEmpty(String property) {
        criteria.add(Restrictions.isNotEmpty(property));
        return this;
    }

    /**
     * Used to restrict a property to be null
     *
     * @param property The property name
    */
    public Query isNull(String property) {
        criteria.add(Restrictions.isNull(property));
        return this;
    }

    /**
     * Used to restrict a property to be not null
     *
     * @param property The property name
    */
    public Query isNotNull(String property) {
        criteria.add(Restrictions.isNotNull(property));
        return this;
    }

    /**
     * Creates an association query
     *
     * @param associationName The assocation name
     * @return The Query instance
     */
    public AssociationQuery createQuery(String associationName) {
        final PersistentProperty property = entity.getPropertyByName(associationName);
        if (property == null || !(property instanceof Association)) {
            throw new InvalidDataAccessResourceUsageException("Cannot query association [" +
                  associationName + "] of class [" + entity +
                  "]. The specified property is not an association.");
        }

        Association association = (Association) property;

        final PersistentEntity associatedEntity = association.getAssociatedEntity();

        return new AssociationQuery(session, associatedEntity, association);
    }

    /**
     * Restricts the results by the given properties value
     *
     * @param value The value to restrict by
     * @return This query instance
     */
    public Query idEq(Object value) {
        value = resolveIdIfEntity(value);

        criteria.add(Restrictions.idEq(value));
        return this;
    }

    /**
     * Used to restrict a value to be greater than the given value
     *
     * @param property The name of the property
     * @param value The value to restrict by
     * @return This query instance
     */
    public Query gt(String property, Object value) {
        criteria.add(Restrictions.gt(property, value));
        return this;
    }

    /**
     * Used to restrict a value to be greater than or equal to the given value
     *
     * @param property The name of the property
     * @param value The value to restrict by
     * @return This query instance
     */
    public Query gte(String property, Object value) {
        criteria.add(Restrictions.gte(property, value));
        return this;
    }

    /**
     * Used to restrict a value to be less than or equal to the given value
     *
     * @param property The name of the property
     * @param value The value to restrict by
     * @return This query instance
     */
    public Query lte(String property, Object value) {
        criteria.add(Restrictions.lte(property, value));
        return this;
    }

    /**
     * Used to restrict a value to be greater than or equal to the given value
     *
     * @param property The name of the property
     * @param value The value to restrict by
     * @return This query instance
     */
    public Query ge(String property, Object value) {
        return gte(property, value);
    }

    /**
     * Used to restrict a value to be less than or equal to the given value
     *
     * @param property The name of the property
     * @param value The value to restrict by
     * @return This query instance
     */
    public Query le(String property, Object value) {
        return lte(property, value);
    }

    /**
     * Used to restrict a value to be less than the given value
     *
     * @param property The name of the property
     * @param value The value to restrict by
     * @return This query instance
     */
    public Query lt(String property, Object value) {
        criteria.add(Restrictions.lt(property, value));
        return this;
    }

    /**
     * Restricts the results by the given property values
     *
     * @param property The name of the property
     * @param values The values to restrict by
     * @return This query instance
     */
    public Query in(String property, List values) {
        criteria.add(Restrictions.in(property, values));
        return this;
    }

    /**
     * Restricts the results by the given property value range
     *
     * @param property The name of the property
     * @param start The start of the range
     * @param end The end of the range
     * @return This query instance
     */
    public Query between(String property, Object start, Object end) {
        criteria.add(Restrictions.between(property, start, end));
        return this;
    }

    /**
     * Restricts the results by the given properties value
     *
     * @param property The name of the property
     * @param expr The expression to restrict by
     * @return This query instance
     */
    public Query like(String property, String expr) {
        criteria.add(Restrictions.like(property, expr));
        return this;
    }

    /**
     * Restricts the results by the given properties value
     *
     * @param property The name of the property
     * @param expr The expression to restrict by
     * @return This query instance
     */
    public Query ilike(String property, String expr) {
        criteria.add(Restrictions.ilike(property, expr));
        return this;
    }

    /**
     * Restricts the results by the given properties value
     *
     * @param property The name of the property
     * @param expr The expression to restrict by
     * @return This query instance
     */
    public Query rlike(String property, String expr) {
        criteria.add(Restrictions.rlike(property, expr));
        return this;
    }

    /**
     * Creates a conjunction using two specified criterion
     *
     * @param a The left hand side
     * @param b The right hand side
     * @return This query instance
     */
    public Query and( Criterion a, Criterion b) {
        Assert.notNull(a, "Left hand side of AND cannot be null");
        Assert.notNull(b, "Right hand side of AND cannot be null");
        criteria.add(Restrictions.and(a, b));
        return this;
    }

    /**
     * Creates a disjunction using two specified criterion
     *
     * @param a The left hand side
     * @param b The right hand side
     * @return This query instance
     */
    public Query or( Criterion a, Criterion b) {
        Assert.notNull(a, "Left hand side of AND cannot be null");
        Assert.notNull(b, "Right hand side of AND cannot be null");
        criteria.add(Restrictions.or(a, b));
        return this;
    }

    /**
     * Executes the query returning zero or many results as a list.
     *
     * @return The results
     */
    public List list() {
        uniqueResult = false;
        flushBeforeQuery();

        ApplicationEventPublisher publisher = session.getDatastore().getApplicationEventPublisher();
        if(publisher != null) {
            publisher.publishEvent(new PreQueryEvent(this));
        }

        List results = executeQuery(entity, criteria);

        if(publisher != null) {
            PostQueryEvent postQueryEvent = new PostQueryEvent(this, results);
            publisher.publishEvent(postQueryEvent);
            results = postQueryEvent.getResults();
        }


        return results;
    }

    /**
     * Executes the query returning a single result or null
     * @return The result
     */
    public Object singleResult() {
        uniqueResult = true;
        List results = list();
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Here purely for compatibility
     *
     * @param uniqueResult Whether it is a unique result
     * @deprecated
     */
    @Deprecated
    public void setUniqueResult(boolean uniqueResult) {
        this.uniqueResult = uniqueResult;
    }

    /**
     * Subclasses should implement this to provide the concrete implementation
     * of querying
     *
     * @param entity The entity
     * @param criteria The criteria
     * @return The results
     */
    protected abstract List executeQuery(PersistentEntity entity, Junction criteria);

    protected Object resolveIdIfEntity(Object value) {
        // use the object id as the value if its a persistent entity
        MappingContext mappingContext = session.getMappingContext();
        if (mappingContext.getProxyFactory().isProxy(value)) {
            return mappingContext.getProxyFactory().getIdentifier(value);
        }
        return mappingContext.isPersistentEntity(value) ? findInstanceId(value) : value;
    }

    private Serializable findInstanceId(Object value) {
        EntityPersister ep = (EntityPersister) session.getPersister(value);
        if (ep != null) {
            return ep.getObjectIdentifier(value);
        }

        return (Serializable)new EntityAccess(session
                .getMappingContext()
                .getPersistentEntity(value.getClass().getName()), value)
                .getIdentifier();
    }

    private Junction disjunction(Junction currentJunction) {
        Disjunction dis = new Disjunction();
        currentJunction.add(dis);
        return dis;
    }

    private Junction conjunction(Junction currentJunction) {
        Conjunction dis = new Conjunction();
        currentJunction.add(dis);
        return dis;
    }

    /**
     * Default behavior is the flush the session before a query in the case of FlushModeType.AUTO.
     * Subclasses can override this method to disable that.
     */
    protected void flushBeforeQuery() {
        // flush before query execution in FlushModeType.AUTO
        if (session.getFlushMode() == FlushModeType.AUTO) {
            session.flush();
        }
    }

    /**
     * A criterion is used to restrict the results of a query
     */
    private void addToJunction(Junction currentJunction, Criterion criterion) {
        if (criterion instanceof PropertyCriterion) {
            final PropertyCriterion pc = (PropertyCriterion) criterion;
            Object value = resolveIdIfEntity(pc.getValue());
            pc.setValue(value);
        }
        if (criterion instanceof AssociationCriteria) {
            AssociationCriteria ac = (AssociationCriteria) criterion;
            AssociationQuery associationQuery = createQuery(ac.getAssociation().getName());
            for (Criterion associationCriterion : ac.getCriteria()) {
                associationQuery.add(associationCriterion);
            }
            currentJunction.add(associationQuery);
        }
        else if (criterion instanceof Junction) {
            Junction j = (Junction) criterion;
            Junction newj;
            if (j instanceof Disjunction) {
                newj= disjunction(currentJunction);
            } else if (j instanceof Negation) {
                newj= negation(currentJunction);
            }
            else {
                newj= conjunction(currentJunction);
            }
            for (Criterion c : j.getCriteria()) {
                addToJunction(newj, c);
            }
        }
        else {
            currentJunction.add(criterion);
        }
    }

    /**
     * Represents a criterion to be used in a criteria query
     */
    public static interface Criterion {}

    /**
     * The ordering of results.
     */
    public static class Order {
        private Direction direction = Direction.ASC;
        private String property;

        public Order(String property) {
            this.property = property;
        }

        public Order(String property, Direction direction) {
            this.direction = direction;
            this.property = property;
        }

        public Direction getDirection() {
            return direction;
        }

        public String getProperty() {
            return property;
        }

        public static Order desc(String property) {
            return new Order(property, Direction.DESC);
        }

        public static Order asc(String property) {
            return new Order(property, Direction.ASC);
        }

        public static enum Direction {
            ASC, DESC
        }
    }

    /**
     * Restricts a property to be null
     */
    public static class IsNull extends PropertyNameCriterion {
        public IsNull(String name) {
            super(name);
        }
    }

    /**
     * Restricts a property to be empty (such as a blank string)
     */
    public static class IsEmpty extends PropertyNameCriterion {
        public IsEmpty(String name) {
            super(name);
        }
    }

    /**
     * Restricts a property to be empty (such as a blank string)
     */
    public static class IsNotEmpty extends PropertyNameCriterion {
        public IsNotEmpty(String name) {
            super(name);
        }
    }

    /**
     * Restricts a property to be not null
     */
    public static class IsNotNull extends PropertyNameCriterion {
        public IsNotNull(String name) {
            super(name);
        }
    }

    /**
     * A Criterion that applies to a property
     */
    public static class PropertyNameCriterion implements Criterion {
        protected String name;

        public PropertyNameCriterion(String name) {
            this.name = name;
        }

        public String getProperty() {
            return name;
        }
    }

    /**
     * A Criterion that compares to properties
     */
    public static class PropertyComparisonCriterion extends PropertyNameCriterion{
        protected String otherProperty;

        public PropertyComparisonCriterion(String property, String otherProperty) {
            super(property);
            this.otherProperty = otherProperty;
        }

        public String getOtherProperty() {
            return otherProperty;
        }
    }

    public static class EqualsProperty extends PropertyComparisonCriterion {
        public EqualsProperty(String property, String otherProperty) {
            super(property, otherProperty);
        }
    }

    public static class NotEqualsProperty extends PropertyComparisonCriterion {
        public NotEqualsProperty(String property, String otherProperty) {
            super(property, otherProperty);
        }
    }

    public static class GreaterThanProperty extends PropertyComparisonCriterion {
        public GreaterThanProperty(String property, String otherProperty) {
            super(property, otherProperty);
        }
    }

    public static class GreaterThanEqualsProperty extends PropertyComparisonCriterion {
        public GreaterThanEqualsProperty(String property, String otherProperty) {
            super(property, otherProperty);
        }
    }

    public static class LessThanProperty extends PropertyComparisonCriterion {
        public LessThanProperty(String property, String otherProperty) {
            super(property, otherProperty);
        }
    }

    public static class LessThanEqualsProperty extends PropertyComparisonCriterion {
        public LessThanEqualsProperty(String property, String otherProperty) {
            super(property, otherProperty);
        }
    }

    /**
     * Criterion that applies to a property and value
     */
    public static class PropertyCriterion extends PropertyNameCriterion {

        protected Object value;

        public PropertyCriterion(String name, Object value) {
            super(name);
            this.value = value;
        }

        public Object getValue() {
            return value;
        }

        public void setValue(Object v) {
            this.value = v;
        }
    }

    /**
     * Used to differentiate criterion that require a subquery
     */
    public static class SubqueryCriterion extends PropertyCriterion {
        public SubqueryCriterion(String name, QueryableCriteria value) {
            super(name, value);
        }
    }

    public static class EqualsAll extends SubqueryCriterion{
        public EqualsAll(String name, QueryableCriteria value) {
            super(name, value);
        }
    }

    public static class NotEqualsAll extends SubqueryCriterion{
        public NotEqualsAll(String name, QueryableCriteria value) {
            super(name, value);
        }
    }

    public static class GreaterThanAll extends SubqueryCriterion{
        public GreaterThanAll(String name, QueryableCriteria value) {
            super(name, value);
        }
    }

    public static class LessThanAll extends SubqueryCriterion{
        public LessThanAll(String name, QueryableCriteria value) {
            super(name, value);
        }
    }

    public static class GreaterThanEqualsAll extends SubqueryCriterion{
        public GreaterThanEqualsAll(String name, QueryableCriteria value) {
            super(name, value);
        }
    }

    public static class LessThanEqualsAll extends SubqueryCriterion{
        public LessThanEqualsAll(String name, QueryableCriteria value) {
            super(name, value);
        }
    }

    /**
     * A criterion that restricts the results based on equality
     */
    public static class Equals extends PropertyCriterion {

        public Equals(String name, Object value) {
            super(name, value);
        }

        @Override
        public void setValue(Object value) {
            this.value = value;
        }
    }

    public static class SizeEquals extends PropertyCriterion{
        public SizeEquals(String name, int value) {
            super(name, value);
        }
    }

    public static class SizeNotEquals extends PropertyCriterion{
        public SizeNotEquals(String name, int value) {
            super(name, value);
        }
    }

    public static class SizeGreaterThan extends PropertyCriterion{
        public SizeGreaterThan(String name, int value) {
            super(name, value);
        }
    }

    public static class SizeGreaterThanEquals extends PropertyCriterion{
        public SizeGreaterThanEquals(String name, int value) {
            super(name, value);
        }
    }

    public static class SizeLessThanEquals extends PropertyCriterion{
        public SizeLessThanEquals(String name, int value) {
            super(name, value);
        }
    }

    public static class SizeLessThan extends PropertyCriterion{
        public SizeLessThan(String name, int value) {
            super(name, value);
        }
    }

    /**
     * A criterion that restricts the results based on the equality of the identifier
     */
    public static class IdEquals extends PropertyCriterion {

        private static final String ID = "id";

        public IdEquals(Object value) {
            super(ID, value);
        }

        @Override
        public void setValue(Object value) {
            this.value = value;
        }
    }

    /**
     * A criterion that restricts the results based on equality
     */
    public static class NotEquals extends PropertyCriterion {

        public NotEquals(String name, Object value) {
            super(name, value);
        }

        @Override
        public void setValue(Object value) {
            this.value = value;
        }
    }

    /**
     * Criterion used to restrict the results based on a list of values
     */
    public static class In extends PropertyCriterion {
        private String name;
        private Collection values = Collections.emptyList();

        public In(String name, Collection values) {
            super(name, values);
            this.name = name;
            this.values = values;
        }

        public String getName() {
            return name;
        }

        @Override
        public String getProperty() {
            return getName();
        }

        public Collection getValues() {
            return Collections.unmodifiableCollection(values);
        }
    }

    /**
     * Used to restrict a value to be greater than the given value
     */
    public static class GreaterThan extends PropertyCriterion {
        public GreaterThan(String name, Object value) {
            super(name, value);
        }
    }

    /**
     * Used to restrict a value to be greater than or equal to the given value
     */
    public static class GreaterThanEquals extends PropertyCriterion {
        public GreaterThanEquals(String name, Object value) {
            super(name, value);
        }
    }

    /**
     * Used to restrict a value to be less than the given value
     */
    public static class LessThan extends PropertyCriterion {
        public LessThan(String name, Object value) {
            super(name, value);
        }
    }

    /**
     * Used to restrict a value to be less than the given value
     */
    public static class LessThanEquals extends PropertyCriterion {
        public LessThanEquals(String name, Object value) {
            super(name, value);
        }
    }

    /**
     * Criterion used to restrict the result to be between values (range query)
     */
    public static class Between extends PropertyCriterion {
        private String property;
        private Object from;
        private Object to;

        public Between(String property, Object from, Object to) {
            super(property, from);
            this.property = property;
            this.from = from;
            this.to = to;
        }

        @Override
        public String getProperty() {
            return property;
        }

        public Object getFrom() {
            return from;
        }

        public Object getTo() {
            return to;
        }
    }

    /**
     * Criterion used to restrict the results based on a pattern (likeness)
     */
    public static class Like extends PropertyCriterion {
        public Like(String name, String expression) {
            super(name, expression);
        }

        public String getPattern() {
            return getValue().toString();
        }
    }

    /**
     * Criterion used to restrict the results based on a pattern (likeness)
     */
    public static class ILike extends Like {
        public ILike(String name, String expression) {
            super(name, expression);
        }
    }

    /**
     * Criterion used to restrict the results based on a regular expression pattern
     */
    public static class RLike extends Like {
        public RLike(String name, String expression) {
            super(name, expression);
        }

        @Override
        public String getPattern() {
            return getValue().toString();
        }
    }

    public static abstract class Junction implements Criterion {
        private List<Criterion> criteria = new ArrayList<Criterion>();

        protected Junction() {
        }

        public Junction(List<Criterion> criteria) {
            this.criteria = criteria;
        }

        public Junction add(Criterion c) {
            if (c != null) {
                if (c instanceof Equals) {
                    final Equals eq = (Equals) c;
                    Object value = eq.getValue();

                    if (value != null) {

                        try {
                            Session session = AbstractDatastore.retrieveSession();
                            final PersistentEntity persistentEntity = session.getMappingContext().getPersistentEntity(value.getClass().getName());
                            if (persistentEntity != null) {
                                EntityPersister ep = (EntityPersister) session.getPersister(value);

                                if (ep != null) {
                                    c = new Equals(eq.getProperty(), ep.getObjectIdentifier(value));
                                }
                            }
                        } catch (ConnectionNotFoundException e) {
                            // continue, use original value
                        }
                    }
                }
                criteria.add(c);
            }
            return this;
        }

        public List<Criterion> getCriteria() {
            return criteria;
        }

        public boolean isEmpty() {
            return criteria.isEmpty();
        }
    }

    /**
     * A Criterion used to combine to criterion in a logical AND
     */
    public static class Conjunction extends Junction {
        public Conjunction() {
        }

        public Conjunction(List<Criterion> criteria) {
            super(criteria);
        }
    }

    /**
     * A Criterion used to combine to criterion in a logical OR
     */
    public static class Disjunction extends Junction {
        public Disjunction() {
        }

        public Disjunction(List<Criterion> criteria) {
            super(criteria);
        }
    }

    /**
     * A criterion used to negate several other criterion
     */
    public static class Negation extends Junction {}

    /**
     * A projection
     */
    public static class Projection {}

    /**
     * A projection used to obtain the identifier of an object
     */
    public static class IdProjection extends Projection {}

    /**
     * Used to count the results of a query
     */
    public static class CountProjection extends Projection {}

    public static class DistinctProjection extends Projection {}

    /**
     * A projection that obtains the value of a property of an entity
     */
    public static class PropertyProjection extends Projection {
        private String propertyName;

        protected PropertyProjection(String propertyName) {
            this.propertyName = propertyName;
        }

        public String getPropertyName() {
            return propertyName;
        }
    }

    public static class DistinctPropertyProjection extends PropertyProjection{
        protected DistinctPropertyProjection(String propertyName) {
            super(propertyName);
        }
    }

    public static class CountDistinctProjection extends PropertyProjection{
        public CountDistinctProjection(String property) {
            super(property);
        }
    }

    /**
     * Computes the average value of a property
     */
    public static class AvgProjection extends PropertyProjection {
        protected AvgProjection(String propertyName) {
            super(propertyName);
        }
    }

    /**
     * Computes the max value of a property
     */
    public static class MaxProjection extends PropertyProjection {
        protected MaxProjection(String propertyName) {
            super(propertyName);
        }
    }

    /**
     * Computes the min value of a property
     */
    public static class MinProjection extends PropertyProjection {
        protected MinProjection(String propertyName) {
            super(propertyName);
        }
    }

    /**
     * Computes the sum of a property
     */
    public static class SumProjection extends PropertyProjection {
        protected SumProjection(String propertyName) {
            super(propertyName);
        }
    }


    /**
     * A list of projections
     */
    public static class ProjectionList implements org.grails.datastore.mapping.query.api.ProjectionList {

        private List<Projection> projections = new ArrayList();

        public List<Projection> getProjectionList() {
            return Collections.unmodifiableList(projections);
        }

        public ProjectionList add(Projection p) {
            projections.add(p);
            return this;
        }

        public org.grails.datastore.mapping.query.api.ProjectionList id() {
            add(Projections.id());
            return this;
        }

        public org.grails.datastore.mapping.query.api.ProjectionList count() {
            add(Projections.count());
            return this;
        }

        public org.grails.datastore.mapping.query.api.ProjectionList countDistinct(String property) {
            add(Projections.countDistinct(property));
            return this;
        }

        public boolean isEmpty() {
            return projections.isEmpty();
        }

        public ProjectionList distinct() {
            return this;
        }

        public org.grails.datastore.mapping.query.api.ProjectionList distinct(String property) {
            add(Projections.distinct(property));
            return this;
        }

        public org.grails.datastore.mapping.query.api.ProjectionList rowCount() {
            return count();
        }

        /**
         * A projection that obtains the value of a property of an entity
         * @param name The name of the property
         * @return The PropertyProjection instance
         */
        public org.grails.datastore.mapping.query.api.ProjectionList property(String name) {
            add(Projections.property(name));
            return this;
        }

        /**
         * Computes the sum of a property
         *
         * @param name The name of the property
         * @return The PropertyProjection instance
         */
        public org.grails.datastore.mapping.query.api.ProjectionList sum(String name) {
            add(Projections.sum(name));
            return this;
        }

        /**
         * Computes the min value of a property
         *
         * @param name The name of the property
         * @return The PropertyProjection instance
         */
        public org.grails.datastore.mapping.query.api.ProjectionList min(String name) {
            add(Projections.min(name));
            return this;
        }

        /**
         * Computes the max value of a property
         *
         * @param name The name of the property
         * @return The PropertyProjection instance
         */
        public org.grails.datastore.mapping.query.api.ProjectionList max(String name) {
            add(Projections.max(name));
            return this;
        }

        /**
         * Computes the average value of a property
         *
         * @param name The name of the property
         * @return The PropertyProjection instance
         */
        public org.grails.datastore.mapping.query.api.ProjectionList avg(String name) {
            add(Projections.avg(name));
            return this;
        }
    }
}
