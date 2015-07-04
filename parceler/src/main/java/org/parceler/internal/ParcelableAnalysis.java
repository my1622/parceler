/**
 * Copyright 2011-2015 John Ericksen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.parceler.internal;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimaps;
import org.androidtransfuse.TransfuseAnalysisException;
import org.androidtransfuse.adapter.*;
import org.androidtransfuse.validation.Validator;
import org.parceler.*;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.util.*;

/**
 * @author John Ericksen
 */
@Singleton
public class ParcelableAnalysis {

    private static final ASTType EMPTY_CONVERTER_TYPE = new ASTStringType(ParcelConverter.EmptyConverter.class.getCanonicalName());
    private static final String GET = "get";
    private static final String IS = "is";
    private static final String SET = "set";
    private static final String[] PREPENDS = {GET, IS, SET};

    private final Map<ASTType, ParcelableDescriptor> parcelableCache = new HashMap<ASTType, ParcelableDescriptor>();
    private final Validator validator;
    private final Provider<Generators> generatorsProvider;

    @Inject
    public ParcelableAnalysis(Validator validator, Provider<Generators> generatorsProvider) {
        this.validator = validator;
        this.generatorsProvider = generatorsProvider;
    }

    public ParcelableDescriptor analyze(ASTType astType) {
        ASTAnnotation parcelASTAnnotation = astType.getASTAnnotation(Parcel.class);
        return analyze(astType, parcelASTAnnotation);
    }

    public ParcelableDescriptor analyze(ASTType astType, ASTAnnotation parcelASTAnnotation) {
        if (!parcelableCache.containsKey(astType)) {
            ParcelableDescriptor parcelableDescriptor = innerAnalyze(astType, parcelASTAnnotation);
            parcelableCache.put(astType, parcelableDescriptor);
        }
        return parcelableCache.get(astType);
    }

    private ParcelableDescriptor innerAnalyze(ASTType astType, ASTAnnotation parcelASTAnnotation) {

        ASTType converter = getConverterType(parcelASTAnnotation);
        Parcel.Serialization serialization = parcelASTAnnotation != null ? parcelASTAnnotation.getProperty("value", Parcel.Serialization.class) : null;
        boolean parcelsIndex = parcelASTAnnotation == null || defaultValue(parcelASTAnnotation.getProperty("parcelsIndex", boolean.class), true);
        ASTType[] interfaces = parcelASTAnnotation != null ? parcelASTAnnotation.getProperty("implementations", ASTType[].class) : new ASTType[0];
        ASTType[] analyze = parcelASTAnnotation != null ? parcelASTAnnotation.getProperty("analyze", ASTType[].class) : new ASTType[0];

        ParcelableDescriptor parcelableDescriptor;

        if (converter != null) {
            parcelableDescriptor = new ParcelableDescriptor(interfaces, converter, parcelsIndex);
        }
        else {
            parcelableDescriptor = new ParcelableDescriptor(interfaces, parcelsIndex);
            Set<MethodSignature> definedMethods = new HashSet<MethodSignature>();
            Map<String, ASTReference<ASTParameter>> writeParameters = new HashMap<String, ASTReference<ASTParameter>>();

            Set<ASTConstructor> constructors = findConstructors(astType, true);
            Set<ASTMethod> factoryMethods = findFactoryMethods(astType);
            ConstructorReference constructorReference = null;
            if(!factoryMethods.isEmpty() && !findConstructors(astType, false).isEmpty()) {
                validator.error("Both @ParcelConstructor and @ParcelFactory may not be annotated on the same class.").element(astType).build();
            }
            else if(factoryMethods.size() == 1){
                ASTMethod factoryMethod = factoryMethods.iterator().next();
                if(!factoryMethod.isStatic()) {
                    validator.error("@ParcelFactory method must be static").element(factoryMethod).build();
                }
                else {
                    writeParameters.putAll(findMethodParameters(factoryMethod));
                    constructorReference = new ConstructorReference(factoryMethods.iterator().next());

                    parcelableDescriptor.setConstructorPair(constructorReference);
                }
            }
            else if(factoryMethods.size() > 1){
                validator.error("Too many @ParcelFactory annotated factory methods.").element(astType).build();
            }
            else if(constructors.size() == 1){
                writeParameters.putAll(findConstructorParameters(constructors.iterator().next()));
                constructorReference = new ConstructorReference(constructors.iterator().next());

                parcelableDescriptor.setConstructorPair(constructorReference);
            }
            else if(constructors.size() == 0){
                validator.error("No @ParcelConstructor annotated constructor and no default empty bean constructor found.").element(astType).build();
            }
            else {
                validator.error("Too many @ParcelConstructor annotated constructors found.").element(astType).build();
            }

            ImmutableSet<ASTType> analyzeSet;
            if(analyze == null){
                analyzeSet = ImmutableSet.of();
            }
            else{
                analyzeSet = FluentIterable.of(analyze).toSet();
            }

            Iterator<ASTType> typeIterator = new ASTTypeHierarchyIterator(astType, analyzeSet);

            while(typeIterator.hasNext()){
                ASTType hierarchyLoop = typeIterator.next();
                Map<String, Collection<ASTReference<ASTMethod>>> defaultWriteMethods = new HashMap<String, Collection<ASTReference<ASTMethod>>>();
                Map<String, Collection<ASTReference<ASTMethod>>> defaultReadMethods = new HashMap<String, Collection<ASTReference<ASTMethod>>>();
                Map<String, Collection<ASTReference<ASTField>>> defaultFields = new HashMap<String, Collection<ASTReference<ASTField>>>();

                if(Parcel.Serialization.BEAN.equals(serialization)){
                    defaultWriteMethods.putAll(findJavaBeanWriteMethods(hierarchyLoop, definedMethods, false));
                    defaultReadMethods.putAll(findJavaBeanReadMethods(hierarchyLoop, definedMethods, false));
                }
                else if(Parcel.Serialization.VALUE.equals(serialization)){
                    defaultWriteMethods.putAll(findValueWriteMethods(hierarchyLoop, definedMethods, false));
                    defaultReadMethods.putAll(findValueReadMethods(hierarchyLoop, definedMethods, false));
                }
                else{
                    defaultFields.putAll(findFields(hierarchyLoop, false));
                }

                Map<String, Collection<ASTReference<ASTMethod>>> propertyWriteMethods = findJavaBeanWriteMethods(hierarchyLoop, definedMethods, true);
                Map<String, Collection<ASTReference<ASTMethod>>> propertyReadMethods = findJavaBeanReadMethods(hierarchyLoop, definedMethods, true);
                Map<String, Collection<ASTReference<ASTField>>> propertyFields = findFields(hierarchyLoop, true);

                //check for > 1 properties
                Map<String, Collection<ASTReference<ASTMethod>>> writeCombination = combine(defaultWriteMethods, propertyWriteMethods);
                Map<String, Collection<ASTReference<ASTMethod>>> readCombination = combine(defaultReadMethods, propertyReadMethods);
                Map<String, Collection<ASTReference<ASTField>>> fieldCombination = combine(defaultFields, propertyFields);
                validateSingleProperty(writeCombination);
                validateSingleProperty(readCombination);
                validateSingleProperty(fieldCombination);

                validateConverters(combine(readCombination, writeCombination), fieldCombination, writeParameters);

                Map<String, AccessibleReference> readReferences = new HashMap<String, AccessibleReference>();
                Map<String, FieldReference> fieldWriteReferences = new HashMap<String, FieldReference>();
                Map<String, MethodReference> methodWriteReferences = new HashMap<String, MethodReference>();
                Map<String, ASTType> converters = new HashMap<String, ASTType>();

                for (Map.Entry<String, Collection<ASTReference<ASTMethod>>> methodEntry : defaultReadMethods.entrySet()) {
                    readReferences.put(methodEntry.getKey(), new MethodReference(astType, hierarchyLoop, methodEntry.getKey(), methodEntry.getValue().iterator().next().getReference().getReturnType(), methodEntry.getValue().iterator().next().getReference()));
                }
                //overwrite with field accessor
                for (Map.Entry<String, Collection<ASTReference<ASTField>>> fieldEntry : defaultFields.entrySet()) {
                    readReferences.put(fieldEntry.getKey(), new FieldReference(hierarchyLoop, fieldEntry.getKey(), fieldEntry.getValue().iterator().next().getReference()));
                    fieldWriteReferences.put(fieldEntry.getKey(), new FieldReference(hierarchyLoop, fieldEntry.getKey(), fieldEntry.getValue().iterator().next().getReference()));
                    if(fieldEntry.getValue().iterator().next().getConverter() != null){
                        converters.put(fieldEntry.getKey(), fieldEntry.getValue().iterator().next().getConverter());
                    }
                }
                //overwrite with property methods
                for (Map.Entry<String, Collection<ASTReference<ASTMethod>>> methodEntry : propertyReadMethods.entrySet()) {
                    readReferences.put(methodEntry.getKey(), new MethodReference(astType, hierarchyLoop, methodEntry.getKey(), methodEntry.getValue().iterator().next().getReference().getReturnType(), methodEntry.getValue().iterator().next().getReference()));
                    if(methodEntry.getValue().iterator().next().getConverter() != null){
                        converters.put(methodEntry.getKey(), methodEntry.getValue().iterator().next().getConverter());
                    }
                }
                //overwrite with property fields
                for (Map.Entry<String, Collection<ASTReference<ASTField>>> fieldEntry : propertyFields.entrySet()) {
                    readReferences.put(fieldEntry.getKey(), new FieldReference(hierarchyLoop, fieldEntry.getKey(), fieldEntry.getValue().iterator().next().getReference()));
                    fieldWriteReferences.put(fieldEntry.getKey(), new FieldReference(hierarchyLoop, fieldEntry.getKey(), fieldEntry.getValue().iterator().next().getReference()));
                    if(fieldEntry.getValue().iterator().next().getConverter() != null){
                        converters.put(fieldEntry.getKey(), fieldEntry.getValue().iterator().next().getConverter());
                    }
                }
                //default write via methods
                for (Map.Entry<String, Collection<ASTReference<ASTMethod>>> methodEntry : defaultWriteMethods.entrySet()) {
                    methodWriteReferences.put(methodEntry.getKey(), new MethodReference(astType, hierarchyLoop, methodEntry.getKey(), methodEntry.getValue().iterator().next().getReference().getParameters().iterator().next().getASTType(), methodEntry.getValue().iterator().next().getReference()));
                    if(methodEntry.getValue().iterator().next().getConverter() != null){
                        converters.put(methodEntry.getKey(), methodEntry.getValue().iterator().next().getConverter());
                    }
                }
                //overwrite with property methods
                for (Map.Entry<String, Collection<ASTReference<ASTMethod>>> methodEntry : propertyWriteMethods.entrySet()) {
                    methodWriteReferences.put(methodEntry.getKey(), new MethodReference(astType, hierarchyLoop, methodEntry.getKey(), methodEntry.getValue().iterator().next().getReference().getParameters().iterator().next().getASTType(), methodEntry.getValue().iterator().next().getReference()));
                    if(methodEntry.getValue().iterator().next().getConverter() != null){
                        converters.put(methodEntry.getKey(), methodEntry.getValue().iterator().next().getConverter());
                    }
                }

                //constructor
                if(constructorReference != null){

                    for (Map.Entry<String, ASTReference<ASTParameter>> parameterEntry : writeParameters.entrySet()) {
                        if(readReferences.containsKey(parameterEntry.getKey())){
                            if(constructorReference.getWriteReferences().containsKey(parameterEntry.getValue().getReference())){
                                validator.error("More than one property found in inheritance hierarchy to match constructor parameter " + parameterEntry.getKey() +
                                        ".  Consider renaming or using a manual ParcelConverter.").element(parameterEntry.getValue().getReference()).build();
                            }
                            else{
                                validateReadReference(readReferences, parameterEntry.getValue().getReference(), parameterEntry.getKey());
                                constructorReference.putReference(parameterEntry.getValue().getReference(), readReferences.get(parameterEntry.getKey()));
                                if(parameterEntry.getValue().getConverter() != null){
                                    constructorReference.putConverter(parameterEntry.getValue().getReference(), parameterEntry.getValue().getConverter());
                                }
                                else {
                                    validateType(parameterEntry.getValue().getReference().getASTType(), parameterEntry.getValue().getReference(), parameterEntry.getValue().getReference().toString());
                                }
                            }
                        }
                    }
                }

                //methods
                for (Map.Entry<String, MethodReference> methodReferenceEntry : methodWriteReferences.entrySet()) {
                    MethodReference methodReference = methodReferenceEntry.getValue();
                    String propertyName = methodReferenceEntry.getKey();
                    if(!writeParameters.containsKey(propertyName) && readReferences.containsKey(propertyName)){
                        validateReadReference(readReferences, methodReference.getMethod(), propertyName);
                        ASTType propertyConverter = converters.containsKey(propertyName) ? converters.get(propertyName) : null;
                        if(propertyConverter == null){
                            validateType(methodReference.getType(), methodReference.getMethod(), methodReference.getOwner().getName() + "#" + methodReference.getName());
                        }
                        parcelableDescriptor.getMethodPairs().add(new ReferencePair<MethodReference>(propertyName, methodReference, readReferences.get(propertyName), propertyConverter));
                    }
                }

                //fields
                for (Map.Entry<String, FieldReference> fieldReferenceEntry : fieldWriteReferences.entrySet()) {
                    FieldReference fieldReference = fieldReferenceEntry.getValue();
                    String propertyName = fieldReferenceEntry.getKey();
                    if(!writeParameters.containsKey(propertyName) &&
                            !methodWriteReferences.containsKey(propertyName) &&
                            readReferences.containsKey(propertyName)){
                        validateReadReference(readReferences, fieldReference.getField(), propertyName);
                        ASTType propertyConverter = converters.containsKey(propertyName) ? converters.get(propertyName) : null;
                        if(propertyConverter == null){
                            validateType(fieldReference.getType(), fieldReference.getField(), fieldReference.getOwner().getName() + "." + fieldReference.getName());
                        }
                        parcelableDescriptor.getFieldPairs().add(new ReferencePair<FieldReference>(propertyName, fieldReference, readReferences.get(propertyName), propertyConverter));
                    }
                }

                //Add all public methods for the ability to determine if they have been overridden in a lower subclass
                for (ASTMethod astMethod : astType.getMethods()) {
                    if(astMethod.getAccessModifier().equals(ASTAccessModifier.PUBLIC)){
                        definedMethods.add(new MethodSignature(astMethod));
                    }
                }
            }

            //validate all constructor parameters have a matching read converter
            if(constructorReference != null && constructorReference.getConstructor() != null){
                for (ASTParameter parameter : constructorReference.getConstructor().getParameters()) {
                    if(!constructorReference.containsWriteReference(parameter)){
                        validator.error("No corresponding property found for constructor parameter " + parameter.getName())
                                .element(parameter).build();
                    }
                }
            }
        }

        if(validator.isInError()){
            return null;
        }

        return parcelableDescriptor;
    }

    private Set<ASTMethod> findFactoryMethods(ASTType astType) {
        Set<ASTMethod> methodResult = new HashSet<ASTMethod>();
        for(ASTMethod method : astType.getMethods()){
            if(method.isAnnotated(ParcelFactory.class)){
                methodResult.add(method);
            }
        }

        return methodResult;
    }

    public Map<String, Collection<ASTReference<ASTMethod>>> findJavaBeanWriteMethods(ASTType astType, Set<MethodSignature> definedMethods, final boolean declaredProperty){
        return findValueMethods(astType, new Predicate<ASTMethod>() {
                    @Override
                    public boolean apply(ASTMethod astMethod) {
                        return isSetter(astMethod, declaredProperty);
                    }
                },
                new Function<ASTMethod, String>() {
                    @Override
                    public String apply(ASTMethod astMethod) {
                        return getPropertyName(astMethod);
                    }
                }, definedMethods, declaredProperty).asMap();
    }

    private Map<String, Collection<ASTReference<ASTMethod>>> findValueWriteMethods(ASTType astType, final Set<MethodSignature> definedMethods, final boolean declaredProperty) {
        return findValueMethods(astType, new Predicate<ASTMethod>() {
                    @Override
                    public boolean apply(ASTMethod astMethod) {
                        return isValueMutator(astMethod, declaredProperty);
                    }
                },
                new Function<ASTMethod, String>(){
                    @Override
                    public String apply(ASTMethod astMethod) {
                        return astMethod.getName();
                    }
                }, definedMethods, declaredProperty).asMap();
    }

    private Map<String, Collection<ASTReference<ASTMethod>>> findJavaBeanReadMethods(ASTType astType, Set<MethodSignature> definedMethods, final boolean declaredProperty){
        return findValueMethods(astType, new Predicate<ASTMethod>() {
                    @Override
                    public boolean apply(ASTMethod astMethod) {
                        return isGetter(astMethod, declaredProperty);
                    }
                },
                new Function<ASTMethod, String>() {
                    @Override
                    public String apply(ASTMethod astMethod) {
                        return getPropertyName(astMethod);
                    }
                }, definedMethods, declaredProperty).asMap();
    }

    private Map<String, Collection<ASTReference<ASTMethod>>> findValueReadMethods(ASTType astType, final Set<MethodSignature> definedMethods, final boolean declaredProperty) {
        return findValueMethods(astType, new Predicate<ASTMethod>() {
                    @Override
                    public boolean apply(ASTMethod astMethod) {
                        return isValueAccessor(astMethod, declaredProperty);
                    }
                },
                new Function<ASTMethod, String>(){
                    @Override
                    public String apply(ASTMethod astMethod) {
                        return astMethod.getName();
                    }
                }, definedMethods, declaredProperty).asMap();
    }

    private HashMultimap<String, ASTReference<ASTMethod>> findValueMethods(ASTType astType, Predicate<ASTMethod> filter, final Function<ASTMethod, String> nameTransformer, final Set<MethodSignature> definedMethods, final boolean declaredProperty) {
        return Multimaps.invertFrom(Multimaps.forMap(FluentIterable.from(astType.getMethods())
                .filter(new Predicate<ASTMethod>() {
                    @Override
                    public boolean apply(ASTMethod astMethod) {
                        return !astMethod.isStatic() &&
                                !astMethod.isAnnotated(Transient.class) &&
                                !definedMethods.contains(new MethodSignature(astMethod)) &&
                                (declaredProperty == astMethod.isAnnotated(ParcelProperty.class));
                    }
                })
                .filter(filter)
                .transform(new Function<ASTMethod, ASTReference<ASTMethod>>() {
                    @Override
                    public ASTReference<ASTMethod> apply(ASTMethod astMethod) {
                        return new ASTReference<ASTMethod>(astMethod, getConverter(astMethod));
                    }
                })
                .toMap(new Function<ASTReference<ASTMethod>, String>() {
                    @Override
                    public String apply(ASTReference<ASTMethod> astMethodReference) {
                        return nameTransformer.apply(astMethodReference.getReference());
                    }
                })), HashMultimap.<String, ASTReference<ASTMethod>>create());
    }

    private Map<String, Collection<ASTReference<ASTField>>> findFields(ASTType astType, final boolean declaredProperty){
        return Multimaps.invertFrom(Multimaps.forMap(FluentIterable.from(astType.getFields())
                .filter(new Predicate<ASTField>() {
                    @Override
                    public boolean apply(ASTField astField) {
                        return !astField.isStatic() &&
                                !astField.isAnnotated(Transient.class) &&
                                !astField.isTransient() &&
                                (declaredProperty == astField.isAnnotated(ParcelProperty.class));
                    }
                })
                .transform(new Function<ASTField, ASTReference<ASTField>>() {
                    @Override
                    public ASTReference<ASTField> apply(ASTField astField) {
                        return new ASTReference<ASTField>(astField, getConverter(astField));
                    }
                })
                .toMap(new Function<ASTReference<ASTField>, String>() {
                    @Override
                    public String apply(ASTReference<ASTField> astFieldReference) {
                        ASTField astField = astFieldReference.getReference();
                        if(astField.isAnnotated(ParcelProperty.class)){
                            return astField.getAnnotation(ParcelProperty.class).value();
                        }
                        return astField.getName();
                    }
                })), HashMultimap.<String, ASTReference<ASTField>>create()).asMap();
    }

    private Set<ASTConstructor> findConstructors(ASTType astType, boolean includeEmptyBeanConstructor){
        Set<ASTConstructor> constructorResult = new HashSet<ASTConstructor>();
        for(ASTConstructor constructor : astType.getConstructors()){
            if(constructor.isAnnotated(ParcelConstructor.class)){
                constructorResult.add(constructor);
            }
        }
        //if none are found, then try to find empty bean constructor
        if(includeEmptyBeanConstructor && constructorResult.isEmpty()){
            for(ASTConstructor constructor : astType.getConstructors()){
                if(constructor.getParameters().isEmpty()){
                    constructorResult.add(constructor);
                }
            }
        }
        return constructorResult;
    }

    private static final class ASTReference<T extends ASTBase>{
        private final ASTType converter;
        private final T reference;

        private ASTReference(T reference, ASTType converter) {
            this.converter = converter;
            this.reference = reference;
        }

        public ASTType getConverter() {
            return converter;
        }

        public T getReference() {
            return reference;
        }
    }

    private Map<String, ASTReference<ASTParameter>> findConstructorParameters(ASTConstructor constructor) {
        Map<String, ASTReference<ASTParameter>> parameters = new HashMap<String, ASTReference<ASTParameter>>();

        for (ASTParameter parameter : constructor.getParameters()) {
            String name = parameter.getName();
            ASTType converter = null;
            if(parameter.isAnnotated(ParcelProperty.class)){
                name = parameter.getAnnotation(ParcelProperty.class).value();
            }
            if(parameter.isAnnotated(ParcelPropertyConverter.class)){
                ASTAnnotation conveterAnnotation = parameter.getASTAnnotation(ParcelPropertyConverter.class);
                converter = conveterAnnotation.getProperty("value", ASTType.class);
            }
            parameters.put(name, new ASTReference<ASTParameter>(parameter, converter));
        }

        return parameters;
    }

    private Map<String, ASTReference<ASTParameter>> findMethodParameters(ASTMethod method) {
        Map<String, ASTReference<ASTParameter>> parameters = new HashMap<String, ASTReference<ASTParameter>>();

        for (ASTParameter parameter : method.getParameters()) {
            String name = parameter.getName();
            ASTType converter = null;
            if(parameter.isAnnotated(ParcelProperty.class)){
                name = parameter.getAnnotation(ParcelProperty.class).value();
            }
            if(parameter.isAnnotated(ParcelPropertyConverter.class)){
                ASTAnnotation conveterAnnotation = parameter.getASTAnnotation(ParcelPropertyConverter.class);
                converter = conveterAnnotation.getProperty("value", ASTType.class);
            }
            parameters.put(name, new ASTReference<ASTParameter>(parameter, converter));
        }

        return parameters;
    }

    private void validateSingleProperty(Map<String, ? extends Collection<? extends ASTReference<? extends ASTBase>>> input){
        for (Map.Entry<String, ? extends Collection<? extends ASTReference<? extends ASTBase>>> entry : input.entrySet()) {
            if(entry.getValue().size() != 1){
                for (ASTReference<? extends ASTBase> reference : entry.getValue()) {
                    validator.error("Too many properties defined under " + entry.getKey())
                            .element(reference.getReference())
                            .build();
                }
            }
        }
    }

    private void validateConverters(Map<String, Collection<ASTReference<ASTMethod>>> input, Map<String, Collection<ASTReference<ASTField>>> fieldReferences, Map<String, ASTReference<ASTParameter>> parameterReferences){
        Set<String> keys = new HashSet<String>();
        keys.addAll(input.keySet());
        keys.addAll(fieldReferences.keySet());
        keys.addAll(parameterReferences.keySet());

        for (String key : keys) {
            boolean found = false;
            if(input.containsKey(key)){
                for (ASTReference<ASTMethod> reference : input.get(key)) {
                    if(reference.getConverter() != null){
                        if(found){
                            validator.error("Only one ParcelConverter may be declared per property")
                                    .element(reference.getReference())
                                    .build();
                        }
                        found = true;
                    }
                }
            }
            if(fieldReferences.containsKey(key)){
                for (ASTReference<ASTField> fieldReference : fieldReferences.get(key)) {
                    if(fieldReference.getConverter() != null){
                        if(found){
                            validator.error("Only one ParcelConverter may be declared per property")
                                    .element(fieldReference.getReference())
                                    .build();
                        }
                        found = true;
                    }
                }
            }
            if(parameterReferences.containsKey(key)){
                ASTReference<ASTParameter> parameterReference = parameterReferences.get(key);
                if(parameterReference.getConverter() != null){
                    if(found){
                        validator.error("Only one ParcelConverter may be declared per property")
                                .element(parameterReference.getReference())
                                .build();
                    }
                }
            }
        }
    }

    private <T extends ASTBase> void validateReadReference(Map<String, AccessibleReference> references, ASTBase mutator, String name){
        if(!references.containsKey(name)){
            validator.error("Accessor not found for property " + name)
                    .element(mutator)
                    .build();
        }
    }

    private void validateType(ASTType type, ASTBase mutator, String where){
        if(!generatorsProvider.get().matches(type)){
            validator.error("Unable to find read/write generator for type " + type + " for " + where)
                    .element(mutator)
                    .build();
        }
    }

    private boolean isGetter(ASTMethod astMethod, boolean ignoreModifier) {
        return astMethod.getParameters().size() == 0 &&
                !astMethod.getReturnType().equals(ASTVoidType.VOID) &&
                (ignoreModifier ||
                (astMethod.getName().startsWith(GET) || astMethod.getName().startsWith(IS)) &&
                        astMethod.getAccessModifier().equals(ASTAccessModifier.PUBLIC));
    }

    private boolean isValueAccessor(ASTMethod astMethod, boolean ignoreModifier){
        return astMethod.getParameters().size() == 0 &&
                !astMethod.getReturnType().equals(ASTVoidType.VOID) &&
                (ignoreModifier || astMethod.getAccessModifier().equals(ASTAccessModifier.PUBLIC));
    }

    private boolean isSetter(ASTMethod astMethod, boolean ignoreModifier) {
        return astMethod.getParameters().size() == 1 &&
                astMethod.getReturnType().equals(ASTVoidType.VOID) &&
                (ignoreModifier ||
                        (astMethod.getName().startsWith(SET) &&
                                astMethod.getAccessModifier().equals(ASTAccessModifier.PUBLIC)));
    }

    private boolean isValueMutator(ASTMethod astMethod, boolean ignoreModifier){
        return astMethod.getParameters().size() == 1 &&
            astMethod.getReturnType().equals(ASTVoidType.VOID) &&
            (ignoreModifier || astMethod.getAccessModifier().equals(ASTAccessModifier.PUBLIC));
    }

    private String getPropertyName(ASTMethod astMethod) {
        String methodName = astMethod.getName();

        if(astMethod.isAnnotated(ParcelProperty.class)){
            return astMethod.getAnnotation(ParcelProperty.class).value();
        }

        for (String prepend : PREPENDS) {
            if (methodName.startsWith(prepend)) {
                String name = methodName.substring(prepend.length());
                return name.substring(0, 1).toLowerCase(Locale.getDefault()) + name.substring(1);
            }
        }
        throw new TransfuseAnalysisException("Unable to convert Method name " + methodName);
    }

    private ASTType getConverter(ASTBase astBase) {
        if(astBase.isAnnotated(ParcelPropertyConverter.class)){
            return astBase.getASTAnnotation(ParcelPropertyConverter.class).getProperty("value", ASTType.class);
        }
        return null;
    }

    private ASTType getConverterType(ASTAnnotation astAnnotation) {
        if(astAnnotation != null){
            ASTType converterType = astAnnotation.getProperty("converter", ASTType.class);
            if(!EMPTY_CONVERTER_TYPE.equals(converterType)){
                return converterType;
            }
        }
        return null;
    }

    private <T> Map<String, Collection<T>> combine(Map<String, Collection<T>> one, Map<String, Collection<T>> two){
        Map<String, Collection<T>> result = new HashMap<String, Collection<T>>();

        for (Map.Entry<String, Collection<T>> oneEntry : one.entrySet()) {
            if(!result.containsKey(oneEntry.getKey())){
                result.put(oneEntry.getKey(), new HashSet<T>());
            }
            result.get(oneEntry.getKey()).addAll(oneEntry.getValue());
        }

        for (Map.Entry<String, Collection<T>> twoEntry : two.entrySet()) {
            if(!result.containsKey(twoEntry.getKey())){
                result.put(twoEntry.getKey(), new HashSet<T>());
            }
            result.get(twoEntry.getKey()).addAll(twoEntry.getValue());
        }
        return result;
    }

    private <T> T defaultValue(T value, T defaultValue){
        if(value == null){
            return defaultValue;
        }
        return value;
    }
}
