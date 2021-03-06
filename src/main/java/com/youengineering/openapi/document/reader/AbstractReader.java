package com.youengineering.openapi.document.reader;

import com.fasterxml.jackson.annotation.JsonView;
import com.youengineering.openapi.config.ContentConfig;
import com.youengineering.openapi.reflection.AnnotatedClassService;
import com.youengineering.openapi.reflection.AnnotatedMethodService;
import com.sun.jersey.api.core.InjectParam;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.util.ParameterProcessor;
import io.swagger.v3.core.util.PathUtils;
import io.swagger.v3.jaxrs2.ResolvedParameter;
import io.swagger.v3.jaxrs2.ext.OpenAPIExtension;
import io.swagger.v3.jaxrs2.ext.OpenAPIExtensions;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tags;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.tags.Tag;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.apache.maven.plugin.logging.Log;
import org.codehaus.plexus.util.StringUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;

import javax.ws.rs.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Function;

import static org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation;
import static org.springframework.core.annotation.AnnotationUtils.findAnnotation;

public abstract class AbstractReader {

    public AbstractReader(AnnotatedClassService annotatedClassService, Log log, ContentConfig contentConfig) {
        this.annotatedClassService = annotatedClassService;
        this.log = log;
        this.contentConfig = contentConfig;
    }
    private AnnotatedClassService annotatedClassService;
    protected Log log;
    private ContentConfig contentConfig;

    public void enrich(OpenAPI openAPI) {
        initializeOpenAPIExtensions(openAPI);
        enrich(openAPI, getApiClasses());
    }

    private void initializeOpenAPIExtensions(OpenAPI openAPI) {
        List<OpenAPIExtension> openAPIExtensions = getSwaggerExtensions();
        for (OpenAPIExtension openAPIExtension : openAPIExtensions) {
            if (openAPIExtension instanceof AbstractReaderOpenAPIExtension) {
                AbstractReaderOpenAPIExtension abstractReaderOpenAPIExtension = (AbstractReaderOpenAPIExtension) openAPIExtension;
                abstractReaderOpenAPIExtension.setContext(this, openAPI);
            }
        }
        OpenAPIExtensions.setExtensions(openAPIExtensions);
    }

    protected abstract List<OpenAPIExtension> getSwaggerExtensions();

    private Set<Class<?>> getApiClasses() {
        Set<Class<?>> apiClasses = new HashSet<>();
        for (Class<? extends Annotation> annotationClass : getApiAnnotationClasses()) {
            apiClasses.addAll(annotatedClassService.getAnnotatedClasses(annotationClass));
        }
        return apiClasses;
    }

    public abstract Set<Class<? extends Annotation>> getApiAnnotationClasses();

    public abstract void enrich(OpenAPI openAPI, Set<Class<?>> classes);

    private <Single extends Annotation, Plural extends Annotation> List<Single> findRepeatableAnnotation(AnnotatedElement annotatedElement, Class<Single> singleAnnotationClass, Class<Plural> pluralAnnotationClass, Function<Plural, Single[]> valuesMethod) {
        List<Single> singleAnnotations = new LinkedList<>();
        Plural pluralAnnotation = findMergedAnnotation(annotatedElement, pluralAnnotationClass);
        if (pluralAnnotation != null) {
            Collections.addAll(singleAnnotations, valuesMethod.apply(pluralAnnotation));
        } else {
            Single singleAnnotation = findMergedAnnotation(annotatedElement, singleAnnotationClass);
            if (singleAnnotation != null) {
                singleAnnotations.add(singleAnnotation);
            }
        }
        return singleAnnotations;
    }

    private boolean hasValidAnnotations(List<Annotation> parameterAnnotations) {
        // Because method parameters can contain parameters that are valid, but
        // not part of the API contract, first check to make sure the parameter
        // has at lease one annotation before processing it.  Also, check a
        // whitelist to make sure that the annotation of the parameter is compatible

        List<Type> validParameterAnnotations = new ArrayList<>();
        validParameterAnnotations.add(BeanParam.class);
        validParameterAnnotations.add(InjectParam.class);
        validParameterAnnotations.add(io.swagger.v3.oas.annotations.Parameter.class);
        validParameterAnnotations.add(PathParam.class);
        validParameterAnnotations.add(QueryParam.class);
        validParameterAnnotations.add(HeaderParam.class);
        validParameterAnnotations.add(FormParam.class);
        // TODO: Only needed in the SpringReader
        validParameterAnnotations.add(org.springframework.web.bind.annotation.CookieValue.class);
        validParameterAnnotations.add(org.springframework.web.bind.annotation.ModelAttribute.class);
        validParameterAnnotations.add(org.springframework.web.bind.annotation.PathVariable.class);
        validParameterAnnotations.add(org.springframework.web.bind.annotation.RequestBody.class);
        validParameterAnnotations.add(org.springframework.web.bind.annotation.RequestHeader.class);
        validParameterAnnotations.add(org.springframework.web.bind.annotation.RequestParam.class);

        boolean hasValidAnnotation = false;
        for (Annotation potentialAnnotation : parameterAnnotations) {
            if (validParameterAnnotations.contains(potentialAnnotation.annotationType())) {
                hasValidAnnotation = true;
                break;
            }
        }

        return hasValidAnnotation;
    }

    private ResolvedParameter getParameters(Type type, List<Annotation> annotations, Components components) {
        return getParameters(type, annotations, new HashSet<>(), components, new String[0], new String[0], null);
    }

    public ResolvedParameter getParameters(Type type, List<Annotation> annotations, Set<Type> typesToSkip, Components components, String[] classTypes, String[] methodTypes, JsonView jsonViewAnnotation) {
        ResolvedParameter resolvedParameter = new ResolvedParameter();
        if (!hasValidAnnotations(annotations) || isApiParamHidden(annotations)) {
            return resolvedParameter;
        }
        Iterator<OpenAPIExtension> chain = OpenAPIExtensions.chain();
        Class<?> cls = TypeUtils.getRawType(type, type);
        log.debug("Looking for path/query/header/cookie params in " + cls);

        if (chain.hasNext()) {
            OpenAPIExtension extension = chain.next();
            log.debug("Trying extension " + extension);
            resolvedParameter = extension.extractParameters(annotations, type, typesToSkip, components, null, null, false, jsonViewAnnotation, chain);
        }

        if (!resolvedParameter.parameters.isEmpty()) {
            for (Parameter parameter : resolvedParameter.parameters) {
                ParameterProcessor.applyAnnotations(parameter, type, annotations, components, classTypes, methodTypes, jsonViewAnnotation);
                if (containsJavaDeprecated(annotations)) {
                    parameter.setDeprecated(true);
                }
            }
        } else {
            log.debug("Looking for body params in " + cls);
            if (!typesToSkip.contains(type)) {
                Parameter param = ParameterProcessor.applyAnnotations(null, type, annotations, components, classTypes, methodTypes, jsonViewAnnotation);
                if (param != null) {
                    resolvedParameter.requestBody = param;
                    if (containsJavaDeprecated(annotations)) {
                        resolvedParameter.requestBody.setDeprecated(true);
                    }
                }
            }
        }
        return resolvedParameter;
    }

    private boolean containsJavaDeprecated(List<Annotation> annotations) {
        return annotations.stream().anyMatch(annotation -> annotation.annotationType() == Deprecated.class);
    }

    private boolean isApiParamHidden(List<Annotation> parameterAnnotations) {
        for (Annotation parameterAnnotation : parameterAnnotations) {
            if (parameterAnnotation instanceof io.swagger.v3.oas.annotations.Parameter) {
                return ((io.swagger.v3.oas.annotations.Parameter) parameterAnnotation).hidden();
            }
        }

        return false;
    }

    private void decorateOperation(Operation operation, Method method) {
        final Iterator<OpenAPIExtension> chain = OpenAPIExtensions.chain();
        if (chain.hasNext()) {
            OpenAPIExtension extension = chain.next();
            extension.decorateOperation(operation, method, chain);
        }
    }

    protected Operation parseAndAddMethod(OpenAPI openAPI, Class<?> clazz, Method method, String httpMethodName, String fullPath, Collection<Parameter> parentParameters) {
        Map<String, Schema> referencedSchemas = new HashMap<>();

        Operation operation;
        io.swagger.v3.oas.annotations.Operation operationAnnotation = findMergedAnnotation(method, io.swagger.v3.oas.annotations.Operation.class);
        if (operationAnnotation != null) {
            AnnotationParserResult<Operation> operationResult = AnnotationParser.parseOperation(operationAnnotation);
            if (operationResult == null) {
                return null;
            }
            operation = operationResult.getResult();
            referencedSchemas.putAll(operationResult.getReferencedSchemas());
        } else {
            operation = new Operation();
            operation.setResponses(new ApiResponses());
        }

        if (operation.getOperationId() == null) {
            operation.setOperationId(generateOperationId(method, httpMethodName));
        }

        io.swagger.v3.oas.annotations.responses.ApiResponses apiResponsesAnnotation = findMergedAnnotation(method, io.swagger.v3.oas.annotations.responses.ApiResponses.class);
        if (apiResponsesAnnotation != null) {
            AnnotationParserResult<ApiResponses> apiResponsesResult = AnnotationParser.parseApiResponses(apiResponsesAnnotation.value());
            operation.getResponses().putAll(apiResponsesResult.getResult());
            referencedSchemas.putAll(apiResponsesResult.getReferencedSchemas());
        }

        // Tags (from class and method)

        List<io.swagger.v3.oas.annotations.tags.Tag> tagAnnotations = new LinkedList<>();
        tagAnnotations.addAll(getTagAnnotations(clazz));
        tagAnnotations.addAll(getTagAnnotations(method));
        for (io.swagger.v3.oas.annotations.tags.Tag tagAnnotation : tagAnnotations) {
            String tagName = tagAnnotation.name();
            if ((operation.getTags() == null) || (!operation.getTags().contains(tagName))) {
                operation.addTagsItem(tagName);
            }
            if ((!tagAnnotation.description().isEmpty())
             || (!tagAnnotation.externalDocs().description().isEmpty())
             || (!tagAnnotation.externalDocs().url().isEmpty())
             || (tagAnnotation.externalDocs().extensions().length > 0)
             || (tagAnnotation.extensions().length > 0)) {
                if ((openAPI.getTags() == null) || openAPI.getTags().stream().noneMatch(tag -> tag.getName().equals(tagName))) {
                    Tag tag = AnnotationParser.parseTag(tagAnnotation);
                    openAPI.addTagsItem(tag);
                }
            }
        }

        // SecurityRequirements (from class and method)

        List<io.swagger.v3.oas.annotations.security.SecurityRequirement> securityRequirementAnnotations = new LinkedList<>();
        securityRequirementAnnotations.addAll(getSecurityRequirementAnnotations(clazz));
        securityRequirementAnnotations.addAll(getSecurityRequirementAnnotations(method));
        for (io.swagger.v3.oas.annotations.security.SecurityRequirement securityRequirementAnnotation : securityRequirementAnnotations) {
            SecurityRequirement securityRequirement = AnnotationParser.parseSecurityRequirement(securityRequirementAnnotation);
            operation.addSecurityItem(securityRequirement);
        }

        // Parameters (from code)

        // TODO: Use consumes
        Set<String> requestMediaTypes = new HashSet<>();
        requestMediaTypes.addAll(getConsumes(clazz));
        requestMediaTypes.addAll(getConsumes(method));

        for (Parameter parentParameter : parentParameters) {
            addParameterIfNotExisting(operation, parentParameter);
        }

        Type[] genericParameterTypes = method.getGenericParameterTypes();
        Annotation[][] paramAnnotations = AnnotatedMethodService.findAllParamAnnotations(method);

        DefaultParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();
        String[] defaultParameterNames = parameterNameDiscoverer.getParameterNames(method);

        for (int i = 0; i < genericParameterTypes.length; i++) {
            Type type = genericParameterTypes[i];
            List<Annotation> annotations = Arrays.asList(paramAnnotations[i]);
            ResolvedParameter resolvedParameter = getParameters(type, annotations, openAPI.getComponents());

            for (Parameter parameter : resolvedParameter.parameters) {
                if (StringUtils.isEmpty(parameter.getName())) {
                    parameter.setName(defaultParameterNames[i]);
                }
                addParameterIfNotExisting(operation, parameter);
            }

            if (resolvedParameter.requestBody != null) {
                RequestBody requestBody = mapRequestBodyParameter(resolvedParameter.requestBody);
                operation.setRequestBody(requestBody);
            }
        }

        Map<String, String> regexMap = new HashMap<>();
        String operationPath = PathUtils.parsePath(fullPath, regexMap);
        if (operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                String pattern = regexMap.get(parameter.getName());
                if (pattern != null) {
                    parameter.getSchema().setPattern(pattern);
                }
            }
        }

        // Responses (from code)

        Set<String> responseMediaTypes = new HashSet<>();
        responseMediaTypes.addAll(getProduces(clazz));
        responseMediaTypes.addAll(getProduces(method));
        if (responseMediaTypes.isEmpty()) {
            responseMediaTypes.add("application/json");
        }

        String responseCode = "200";
        // TODO: An improvement would be to support generating the schema from code and enrich it with the info from the annotation
        if (!operation.getResponses().containsKey(responseCode)) {
            Type responseType = resolveResponseType(method.getGenericReturnType());
            if (!responseType.equals(Void.class) && !responseType.equals(void.class) && hasResponseContent(responseType, method, httpMethodName)) {
                ApiResponse response = new ApiResponse();
                // It's preferred to have a non-empty default description, as certain tools like io.swagger.parser.OpenAPIParser ignore empty strings, leading to a parsing error
                response.setDescription(contentConfig.getDefaultSuccessfulOperationDescription());
                Content content = new Content();
                ResolvedSchema resolvedSchema = TypeUtil.getResolvedSchema(responseType);
                for (String responseMediaType : responseMediaTypes) {
                    MediaType mediaType = content.computeIfAbsent(responseMediaType, mt -> new MediaType());
                    mediaType.setSchema(resolvedSchema.schema);
                }
                response.setContent(content);
                operation.getResponses().put(responseCode, response);
                referencedSchemas.putAll(resolvedSchema.referencedSchemas);
            }
        }

        Map<Integer, String> responseDescriptions = getResponseDescriptions(method);
        updateResponseStatiDescriptions(operation, responseDescriptions);

        if (operation.getResponses().isEmpty()) {
            operation.getResponses().put("default", new ApiResponse().description(contentConfig.getDefaultSuccessfulOperationDescription()));
        }

        if (findAnnotation(method, Deprecated.class) != null) {
            operation.setDeprecated(true);
        }

        // Decorate

        decorateOperation(operation, method);

        // Add operation

        PathItem path = openAPI.getPaths().computeIfAbsent(operationPath, op -> new PathItem());
        PathItem.HttpMethod httpMethod = PathItem.HttpMethod.valueOf(httpMethodName.toUpperCase());
        path.operation(httpMethod, operation);

        // Referenced schemas

        for (Map.Entry<String, Schema> referencedSchemaEntry : referencedSchemas.entrySet()) {
            openAPI.getComponents().addSchemas(referencedSchemaEntry.getKey(), referencedSchemaEntry.getValue());
        }

        return operation;
    }

    private String generateOperationId(Method method, String httpMethodName) {
  		String operationId = contentConfig.getOperationIdFormat();
        operationId = operationId.replaceAll("\\{\\{packageName}}", method.getDeclaringClass().getPackage().getName());
        operationId = operationId.replaceAll("\\{\\{className}}", method.getDeclaringClass().getSimpleName());
        operationId = operationId.replaceAll("\\{\\{methodName}}", method.getName());
        operationId = operationId.replaceAll("\\{\\{httpMethod}}", httpMethodName);
  		return operationId;
    }

    private RequestBody mapRequestBodyParameter(Parameter parameter) {
        RequestBody requestBody = new RequestBody();
        requestBody.setDescription(parameter.getDescription());
        requestBody.setRequired(parameter.getRequired());
        requestBody.setExtensions(parameter.getExtensions());
        requestBody.set$ref(parameter.get$ref());
        Content content = new Content();
        MediaType mediaType = new MediaType();
        mediaType.setSchema(parameter.getSchema());
        mediaType.setExamples(parameter.getExamples());
        mediaType.setExample(parameter.getExample());
        content.put("application/json", mediaType);
        requestBody.setContent(content);
        return requestBody;
    }

    private List<io.swagger.v3.oas.annotations.tags.Tag> getTagAnnotations(AnnotatedElement annotatedElement) {
        return findRepeatableAnnotation(
                annotatedElement,
                io.swagger.v3.oas.annotations.tags.Tag.class,
                Tags.class,
                Tags::value
        );
    }

    private List<io.swagger.v3.oas.annotations.security.SecurityRequirement> getSecurityRequirementAnnotations(AnnotatedElement annotatedElement) {
        return findRepeatableAnnotation(
                annotatedElement,
                io.swagger.v3.oas.annotations.security.SecurityRequirement.class,
                SecurityRequirements.class,
                SecurityRequirements::value
        );
    }

    private void addParameterIfNotExisting(Operation operation, Parameter parameter) {
        if (operation.getParameters() != null) {
            for (Parameter existingParameter : operation.getParameters()) {
                if (existingParameter.getName().equals(parameter.getName())) {
                    return;
                }
            }
        }
        operation.addParametersItem(parameter);
    }

    private void updateResponseStatiDescriptions(Operation operation, Map<Integer, String> responseDescriptions) {
        ApiResponses responses = operation.getResponses();
        for (Map.Entry<Integer, String> responseDescriptionEntry : responseDescriptions.entrySet()) {
            Integer code = responseDescriptionEntry.getKey();
            String description = responseDescriptionEntry.getValue();
            String codeText = "" + code;
            ApiResponse response = responses.get(codeText);
            if (response != null) {
                if (StringUtils.isNotEmpty(description)) {
                    response.setDescription(description);
                }
            } else {
                response = new ApiResponse();
                response.setDescription(description);
                responses.put(codeText, response);
            }
        }
    }

	protected Type resolveResponseType(Type methodType) {
        return methodType;
    }

    protected abstract boolean hasResponseContent(Type responseClassType, Method method, String httpMethod);

    protected abstract Map<Integer, String> getResponseDescriptions(Method method);

    protected abstract Collection<String> getConsumes(AnnotatedElement annotatedElement);

    protected abstract Collection<String> getProduces(AnnotatedElement annotatedElement);
}
