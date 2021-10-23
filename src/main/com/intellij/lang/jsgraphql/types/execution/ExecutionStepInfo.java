/*
    The MIT License (MIT)

    Copyright (c) 2015 Andreas Marek and Contributors

    Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files
    (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge,
    publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do
    so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
    OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
    LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
    CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.intellij.lang.jsgraphql.types.execution;

import com.intellij.lang.jsgraphql.types.PublicApi;
import com.intellij.lang.jsgraphql.types.collect.ImmutableMapWithNullValues;
import com.intellij.lang.jsgraphql.types.schema.*;

import java.util.Map;
import java.util.function.Consumer;

import static com.intellij.lang.jsgraphql.types.Assert.assertNotNull;
import static com.intellij.lang.jsgraphql.types.Assert.assertTrue;
import static com.intellij.lang.jsgraphql.types.schema.GraphQLTypeUtil.isList;

/**
 * As the graphql query executes, it forms a hierarchy from parent fields (and their type) to their child fields (and their type)
 * until a scalar type is encountered; this class captures that execution type information.
 * <p>
 * The static graphql type system (rightly) does not contain a hierarchy of child to parent types nor the nonnull ness of
 * type instances, so this helper class adds this information during query execution.
 */
@PublicApi
public class ExecutionStepInfo {

    /**
     * An ExecutionStepInfo represent either a field or a list element inside a list of objects/interfaces/unions.
     *
     * A StepInfo never represent a Scalar/Enum inside a list (e.g. [String]) because GraphQL execution doesn't descend down
     * scalar/enums lists.
     *
     */

    /**
     * If this StepInfo represent a field the type is equal to fieldDefinition.getType()
     * <p>
     * if this StepInfo is a list element this type is the actual current list element. For example:
     * Query.pets: [[Pet]] with Pet either a Dog or Cat and the actual result is [[Dog1],[[Cat1]]
     * Then the type is (for a query "{pets{name}}"):
     * [[Pet]] for /pets (representing the field Query.pets, not a list element)
     * [Pet] fot /pets[0]
     * [Pet] for /pets[1]
     * Dog for /pets[0][0]
     * Cat for /pets[1][0]
     * String for /pets[0][0]/name (representing the field Dog.name, not a list element)
     * String for /pets[1][0]/name (representing the field Cat.name, not a list element)
     */
    private final GraphQLOutputType type;

    /**
     * A list element is characterized by having a path ending with an index segment. (ResultPath.isListSegment())
     */
    private final ResultPath path;
    private final ExecutionStepInfo parent;

    /**
     * field, fieldDefinition, fieldContainer and arguments differ per field StepInfo.
     * <p>
     * But for list StepInfos these properties are the same as the field returning the list.
     */
    private final MergedField field;
    private final GraphQLFieldDefinition fieldDefinition;
    private final GraphQLObjectType fieldContainer;
    private final ImmutableMapWithNullValues<String, Object> arguments;

    private ExecutionStepInfo(GraphQLOutputType type,
                              GraphQLFieldDefinition fieldDefinition,
                              MergedField field,
                              ResultPath path,
                              ExecutionStepInfo parent,
                              ImmutableMapWithNullValues<String, Object> arguments,
                              GraphQLObjectType fieldsContainer) {
        this.fieldDefinition = fieldDefinition;
        this.field = field;
        this.path = path;
        this.parent = parent;
        this.type = assertNotNull(type, () -> "you must provide a graphql type");
        this.arguments = arguments;
        this.fieldContainer = fieldsContainer;
    }

    /**
     * @return the GraphQLObjectType defining the {@link #getFieldDefinition()}
     * @deprecated use {@link #getObjectType()} instead as it is named better
     * @see ExecutionStepInfo#getObjectType()
     */
    @Deprecated
    public GraphQLObjectType getFieldContainer() {
        return fieldContainer;
    }

    /**
     * The GraphQLObjectType where fieldDefinition is defined.
     * Note:
     * For the Introspection field __typename the returned object type doesn't actually contain the fieldDefinition.
     *
     * @return the GraphQLObjectType defining the {@link #getFieldDefinition()}
     */
    public GraphQLObjectType getObjectType() {
        return fieldContainer;
    }

    /**
     * This returns the type for the current step.
     *
     * @return the graphql type in question
     */
    public GraphQLOutputType getType() {
        return type;
    }

    /**
     * This returns the type which is unwrapped if it was {@link GraphQLNonNull} wrapped
     *
     * @return the graphql type in question
     */
    public GraphQLOutputType getUnwrappedNonNullType() {
        return (GraphQLOutputType) GraphQLTypeUtil.unwrapNonNull(this.type);
    }

    /**
     * This returns the field definition that is in play when this type info was created or null
     * if the type is a root query type
     *
     * @return the field definition or null if there is not one
     */
    public GraphQLFieldDefinition getFieldDefinition() {
        return fieldDefinition;
    }

    /**
     * This returns the AST fields that matches the {@link #getFieldDefinition()} during execution
     *
     * @return the  merged fields
     */
    public MergedField getField() {
        return field;
    }

    /**
     * @return the {@link ResultPath} to this info
     */
    public ResultPath getPath() {
        return path;
    }

    /**
     * @return true if the type must be nonnull
     */
    public boolean isNonNullType() {
        return GraphQLTypeUtil.isNonNull(this.type);
    }

    /**
     * @return true if the type is a list
     */
    public boolean isListType() {
        return isList(type);
    }

    /**
     * @return the resolved arguments that have been passed to this field
     */
    public Map<String, Object> getArguments() {
        return arguments;
    }

    /**
     * Returns the named argument
     *
     * @param name the name of the argument
     * @param <T>  you decide what type it is
     * @return the named argument or null if its not present
     */
    @SuppressWarnings("unchecked")
    public <T> T getArgument(String name) {
        return (T) arguments.get(name);
    }

    /**
     * @return the parent type information
     */
    public ExecutionStepInfo getParent() {
        return parent;
    }

    /**
     * @return true if the type has a parent (most do)
     */
    public boolean hasParent() {
        return parent != null;
    }

    /**
     * This allows you to morph a type into a more specialized form yet return the same
     * parent and non-null ness, for example taking a {@link GraphQLInterfaceType}
     * and turning it into a specific {@link com.intellij.lang.jsgraphql.types.schema.GraphQLObjectType}
     * after type resolution has occurred
     *
     * @param newType the new type to be
     * @return a new type info with the same
     */
    public ExecutionStepInfo changeTypeWithPreservedNonNull(GraphQLOutputType newType) {
        assertTrue(!GraphQLTypeUtil.isNonNull(newType), () -> "newType can't be non null");
        if (isNonNullType()) {
            return new ExecutionStepInfo(GraphQLNonNull.nonNull(newType), fieldDefinition, field, path, this.parent, arguments, this.fieldContainer);
        } else {
            return new ExecutionStepInfo(newType, fieldDefinition, field, path, this.parent, arguments, this.fieldContainer);
        }
    }


    /**
     * @return the type in graphql SDL format, eg [typeName!]!
     */
    public String simplePrint() {
        return GraphQLTypeUtil.simplePrint(type);
    }

    @Override
    public String toString() {
        return "ExecutionStepInfo{" +
                " path=" + path +
                ", type=" + type +
                ", fieldDefinition=" + fieldDefinition +
                '}';
    }

    public ExecutionStepInfo transform(Consumer<Builder> builderConsumer) {
        Builder builder = new Builder(this);
        builderConsumer.accept(builder);
        return builder.build();
    }

    public String getResultKey() {
        return field.getResultKey();
    }

    /**
     * @return a builder of type info
     */
    public static Builder newExecutionStepInfo() {
        return new Builder();
    }

    public static Builder newExecutionStepInfo(ExecutionStepInfo existing) {
        return new Builder(existing);
    }

    public static class Builder {
        GraphQLOutputType type;
        ExecutionStepInfo parentInfo;
        GraphQLFieldDefinition fieldDefinition;
        GraphQLObjectType fieldContainer;
        MergedField field;
        ResultPath path;
        ImmutableMapWithNullValues<String, Object> arguments;

        /**
         * @see ExecutionStepInfo#newExecutionStepInfo()
         */
        private Builder() {
            arguments = ImmutableMapWithNullValues.emptyMap();
        }

        private Builder(ExecutionStepInfo existing) {
            this.type = existing.type;
            this.parentInfo = existing.parent;
            this.fieldDefinition = existing.fieldDefinition;
            this.fieldContainer = existing.fieldContainer;
            this.field = existing.field;
            this.path = existing.path;
            this.arguments = ImmutableMapWithNullValues.copyOf(existing.getArguments());
        }

        public Builder type(GraphQLOutputType type) {
            this.type = type;
            return this;
        }

        public Builder parentInfo(ExecutionStepInfo executionStepInfo) {
            this.parentInfo = executionStepInfo;
            return this;
        }

        public Builder fieldDefinition(GraphQLFieldDefinition fieldDefinition) {
            this.fieldDefinition = fieldDefinition;
            return this;
        }

        public Builder field(MergedField field) {
            this.field = field;
            return this;
        }

        public Builder path(ResultPath resultPath) {
            this.path = resultPath;
            return this;
        }

        public Builder arguments(Map<String, Object> arguments) {
            this.arguments = arguments == null ? ImmutableMapWithNullValues.emptyMap() : ImmutableMapWithNullValues.copyOf(arguments);
            return this;
        }

        public Builder fieldContainer(GraphQLObjectType fieldContainer) {
            this.fieldContainer = fieldContainer;
            return this;
        }

        public ExecutionStepInfo build() {
            return new ExecutionStepInfo(type, fieldDefinition, field, path, parentInfo, arguments, fieldContainer);
        }
    }
}
