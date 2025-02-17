package org.jetbrains.kotlin.ui.editors.completion

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.core.resolve.KotlinResolutionFacade
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.idea.util.*
import org.jetbrains.kotlin.incremental.KotlinLookupLocation
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.load.kotlin.toSourceElement
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.bindingContextUtil.getDataFlowInfoBefore
import org.jetbrains.kotlin.resolve.calls.smartcasts.SmartCastManager
import org.jetbrains.kotlin.resolve.deprecation.DeprecationResolver
import org.jetbrains.kotlin.resolve.descriptorUtil.isExtension
import org.jetbrains.kotlin.resolve.scopes.*
import org.jetbrains.kotlin.resolve.scopes.receivers.ClassQualifier
import org.jetbrains.kotlin.resolve.scopes.utils.collectDescriptorsFiltered
import org.jetbrains.kotlin.resolve.scopes.utils.memberScopeAsImportingScope
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.synthetic.SyntheticJavaPropertyDescriptor
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.expressions.DoubleColonLHS
import org.jetbrains.kotlin.types.typeUtil.isUnit
import org.jetbrains.kotlin.ui.refactorings.extract.parentsWithSelf
import java.util.*

class KotlinReferenceVariantsHelper(
    val bindingContext: BindingContext,
    private val resolutionFacade: KotlinResolutionFacade,
    private val moduleDescriptor: ModuleDescriptor,
    val visibilityFilter: (DeclarationDescriptor) -> Boolean
) {
    fun getReferenceVariants(
        simpleNameExpression: KtSimpleNameExpression,
        kindFilter: DescriptorKindFilter,
        nameFilter: (Name) -> Boolean
    ): Collection<DeclarationDescriptor> {
        val callTypeAndReceiver = CallTypeAndReceiver.detect(simpleNameExpression)
        var variants: Collection<DeclarationDescriptor> =
            getReferenceVariants(simpleNameExpression, callTypeAndReceiver, kindFilter, nameFilter)
                .filter {
                    !resolutionFacade.frontendService<DeprecationResolver>().isHiddenInResolution(it) && visibilityFilter(
                        it
                    )
                }

        ShadowedDeclarationsFilter.create(bindingContext, resolutionFacade, simpleNameExpression, callTypeAndReceiver)
            ?.let {
            variants = it.filter(variants)
            }
        if (kindFilter.kindMask.and(DescriptorKindFilter.FUNCTIONS_MASK) != 0) {
            variants = filterOutJavaGettersAndSetters(variants)
        }

        if (kindFilter.kindMask.and(DescriptorKindFilter.VARIABLES_MASK) != 0) {
            variants = excludeNonInitializedVariable(variants, simpleNameExpression)
        }

        return variants
    }

    private fun getVariantsForImportOrPackageDirective(
        receiverExpression: KtExpression?,
        kindFilter: DescriptorKindFilter,
        nameFilter: (Name) -> Boolean
    ): Collection<DeclarationDescriptor> {
        if (receiverExpression != null) {
            val qualifier = bindingContext[BindingContext.QUALIFIER, receiverExpression] ?: return emptyList()
            val staticDescriptors = qualifier.staticScope.collectStaticMembers(resolutionFacade, kindFilter, nameFilter)

            val objectDescriptor = (qualifier as? ClassQualifier)?.descriptor?.takeIf { it.kind == ClassKind.OBJECT }
                ?: return staticDescriptors

            return staticDescriptors + objectDescriptor.defaultType.memberScope.getDescriptorsFiltered(
                kindFilter,
                nameFilter
            )
        } else {
            val rootPackage = resolutionFacade.moduleDescriptor.getPackage(FqName.ROOT)
            return rootPackage.memberScope.getDescriptorsFiltered(kindFilter, nameFilter)
        }
    }

    private fun getVariantsForUserType(
        receiverExpression: KtExpression?,
        contextElement: PsiElement,
        kindFilter: DescriptorKindFilter,
        nameFilter: (Name) -> Boolean
    ): Collection<DeclarationDescriptor> {
        if (receiverExpression != null) {
            val qualifier = bindingContext[BindingContext.QUALIFIER, receiverExpression] ?: return emptyList()
            return qualifier.staticScope.collectStaticMembers(resolutionFacade, kindFilter, nameFilter)
        } else {
            val scope = contextElement.getResolutionScope(bindingContext, resolutionFacade)
            return scope.collectDescriptorsFiltered(kindFilter, nameFilter, changeNamesForAliased = true)
        }
    }

    private fun getVariantsForCallableReference(
        callTypeAndReceiver: CallTypeAndReceiver.CALLABLE_REFERENCE,
        contextElement: PsiElement,
        useReceiverType: KotlinType?,
        kindFilter: DescriptorKindFilter,
        nameFilter: (Name) -> Boolean
    ): Collection<DeclarationDescriptor> {
        val descriptors = LinkedHashSet<DeclarationDescriptor>()

        val resolutionScope = contextElement.getResolutionScope(bindingContext, resolutionFacade)

        val receiver = callTypeAndReceiver.receiver
        if (receiver != null) {
            val isStatic = bindingContext[BindingContext.DOUBLE_COLON_LHS, receiver] is DoubleColonLHS.Type

            val explicitReceiverTypes: Collection<KotlinType> = useReceiverType?.let {
                listOf(useReceiverType)
            } ?: callTypeAndReceiver.receiverTypes(
                bindingContext,
                contextElement,
                moduleDescriptor,
                resolutionFacade,
                stableSmartCastsOnly = false
            )!!

            val constructorFilter = { descriptor: ClassDescriptor -> if (isStatic) true else descriptor.isInner }
            descriptors.addNonExtensionMembers(explicitReceiverTypes, kindFilter, nameFilter, constructorFilter)

            descriptors.addScopeAndSyntheticExtensions(
                resolutionScope,
                explicitReceiverTypes,
                CallType.CALLABLE_REFERENCE,
                kindFilter,
                nameFilter
            )

            if (isStatic) {
                explicitReceiverTypes
                    .mapNotNull { (it.constructor.declarationDescriptor as? ClassDescriptor)?.staticScope }
                    .flatMapTo(descriptors) { it.collectStaticMembers(resolutionFacade, kindFilter, nameFilter) }
            }
        } else {
            descriptors.addNonExtensionCallablesAndConstructors(
                resolutionScope,
                kindFilter, nameFilter, constructorFilter = { !it.isInner },
                classesOnly = false
            )
        }
        return descriptors
    }

    private fun getReferenceVariants(
        simpleNameExpression: KtSimpleNameExpression,
        callTypeAndReceiver: CallTypeAndReceiver<*, *>,
        kindFilter: DescriptorKindFilter,
        nameFilter: (Name) -> Boolean
    ): Collection<DeclarationDescriptor> {
        val callType = callTypeAndReceiver.callType

        @Suppress("NAME_SHADOWING")
        val kindFilter = kindFilter.intersect(callType.descriptorKindFilter)

        val receiverExpression: KtExpression?
        when (callTypeAndReceiver) {
            is CallTypeAndReceiver.IMPORT_DIRECTIVE -> {
                return getVariantsForImportOrPackageDirective(callTypeAndReceiver.receiver, kindFilter, nameFilter)
            }

            is CallTypeAndReceiver.PACKAGE_DIRECTIVE -> {
                return getVariantsForImportOrPackageDirective(callTypeAndReceiver.receiver, kindFilter, nameFilter)
            }

            is CallTypeAndReceiver.TYPE -> {
                return getVariantsForUserType(
                    callTypeAndReceiver.receiver,
                    simpleNameExpression,
                    kindFilter,
                    nameFilter
                )
            }

            is CallTypeAndReceiver.ANNOTATION -> {
                return getVariantsForUserType(
                    callTypeAndReceiver.receiver,
                    simpleNameExpression,
                    kindFilter,
                    nameFilter
                )
            }

            is CallTypeAndReceiver.CALLABLE_REFERENCE -> {
                return getVariantsForCallableReference(
                    callTypeAndReceiver,
                    simpleNameExpression,
                    null,
                    kindFilter,
                    nameFilter
                )
            }

            is CallTypeAndReceiver.DEFAULT -> receiverExpression = null
            is CallTypeAndReceiver.DOT -> receiverExpression = callTypeAndReceiver.receiver
            is CallTypeAndReceiver.SUPER_MEMBERS -> receiverExpression = callTypeAndReceiver.receiver
            is CallTypeAndReceiver.SAFE -> receiverExpression = callTypeAndReceiver.receiver
            is CallTypeAndReceiver.INFIX -> receiverExpression = callTypeAndReceiver.receiver
            is CallTypeAndReceiver.OPERATOR -> return emptyList()
            is CallTypeAndReceiver.UNKNOWN -> return emptyList()
            else -> throw RuntimeException()
        }

        val resolutionScope = simpleNameExpression.getResolutionScope(bindingContext, resolutionFacade)
        val dataFlowInfo = bindingContext.getDataFlowInfoBefore(simpleNameExpression)
        val containingDeclaration = resolutionScope.ownerDescriptor

        val smartCastManager = resolutionFacade.frontendService<SmartCastManager>()
        val languageVersionSettings = resolutionFacade.frontendService<LanguageVersionSettings>()

        val implicitReceiverTypes = resolutionScope.getImplicitReceiversWithInstance(
            languageVersionSettings.supportsFeature(LanguageFeature.DslMarkersSupport)
        ).flatMap {
            smartCastManager.getSmartCastVariantsWithLessSpecificExcluded(
                it.value,
                bindingContext,
                containingDeclaration,
                dataFlowInfo,
                languageVersionSettings,
                resolutionFacade.frontendService()
            )
        }.toSet()

        val descriptors = LinkedHashSet<DeclarationDescriptor>()

        val filterWithoutExtensions = kindFilter exclude DescriptorKindExclude.Extensions
        if (receiverExpression != null) {
            val qualifier = bindingContext[BindingContext.QUALIFIER, receiverExpression]
            if (qualifier != null) {
                descriptors.addAll(
                    qualifier.staticScope.collectStaticMembers(
                        resolutionFacade,
                        filterWithoutExtensions,
                        nameFilter
                    )
                )
            }

            val explicitReceiverTypes = callTypeAndReceiver.receiverTypes(
                bindingContext,
                simpleNameExpression,
                moduleDescriptor,
                resolutionFacade,
                stableSmartCastsOnly = false
            )!!

            descriptors.processAll(
                implicitReceiverTypes,
                explicitReceiverTypes,
                resolutionScope,
                callType,
                kindFilter,
                nameFilter
            )
        } else {
            descriptors.processAll(
                implicitReceiverTypes,
                implicitReceiverTypes,
                resolutionScope,
                callType,
                kindFilter,
                nameFilter
            )

            descriptors.addAll(
                resolutionScope.collectDescriptorsFiltered(
                    filterWithoutExtensions,
                    nameFilter,
                    changeNamesForAliased = true
                )
            )
        }

        if (callType == CallType.SUPER_MEMBERS) { // we need to unwrap fake overrides in case of "super." because ShadowedDeclarationsFilter does not work correctly
            return descriptors.flatMapTo(LinkedHashSet<DeclarationDescriptor>()) {
                if (it is CallableMemberDescriptor && it.kind == CallableMemberDescriptor.Kind.FAKE_OVERRIDE) it.overriddenDescriptors else listOf(
                    it
                )
            }
        }

        return descriptors
    }

    private fun MutableSet<DeclarationDescriptor>.processAll(
        implicitReceiverTypes: Collection<KotlinType>,
        receiverTypes: Collection<KotlinType>,
        resolutionScope: LexicalScope,
        callType: CallType<*>,
        kindFilter: DescriptorKindFilter,
        nameFilter: (Name) -> Boolean
    ) {
        addNonExtensionMembers(receiverTypes, kindFilter, nameFilter, constructorFilter = { it.isInner })
        addMemberExtensions(implicitReceiverTypes, receiverTypes, callType, kindFilter, nameFilter)
        addScopeAndSyntheticExtensions(resolutionScope, receiverTypes, callType, kindFilter, nameFilter)
    }

    private fun MutableSet<DeclarationDescriptor>.addMemberExtensions(
        dispatchReceiverTypes: Collection<KotlinType>,
        extensionReceiverTypes: Collection<KotlinType>,
        callType: CallType<*>,
        kindFilter: DescriptorKindFilter,
        nameFilter: (Name) -> Boolean
    ) {
        val memberFilter = kindFilter exclude DescriptorKindExclude.NonExtensions
        for (dispatchReceiverType in dispatchReceiverTypes) {
            for (member in dispatchReceiverType.memberScope.getDescriptorsFiltered(memberFilter, nameFilter)) {
                addAll((member as CallableDescriptor).substituteExtensionIfCallable(extensionReceiverTypes, callType))
            }
        }
    }

    private fun MutableSet<DeclarationDescriptor>.addNonExtensionMembers(
        receiverTypes: Collection<KotlinType>,
        kindFilter: DescriptorKindFilter,
        nameFilter: (Name) -> Boolean,
        constructorFilter: (ClassDescriptor) -> Boolean
    ) {
        for (receiverType in receiverTypes) {
            addNonExtensionCallablesAndConstructors(
                receiverType.memberScope.memberScopeAsImportingScope(),
                kindFilter, nameFilter, constructorFilter,
                false
            )
            receiverType.constructor.supertypes.forEach {
                addNonExtensionCallablesAndConstructors(
                    it.memberScope.memberScopeAsImportingScope(),
                    kindFilter, nameFilter, constructorFilter,
                    true
                )
            }
        }
    }

    private fun MutableSet<DeclarationDescriptor>.addNonExtensionCallablesAndConstructors(
        scope: HierarchicalScope,
        kindFilter: DescriptorKindFilter,
        nameFilter: (Name) -> Boolean,
        constructorFilter: (ClassDescriptor) -> Boolean,
        classesOnly: Boolean
    ) {
        var filterToUse = DescriptorKindFilter(kindFilter.kindMask and DescriptorKindFilter.CALLABLES.kindMask).exclude(
            DescriptorKindExclude.Extensions
        )

        // should process classes if we need constructors
        if (filterToUse.acceptsKinds(DescriptorKindFilter.FUNCTIONS_MASK)) {
            filterToUse = filterToUse.withKinds(DescriptorKindFilter.NON_SINGLETON_CLASSIFIERS_MASK)
        }

        for (descriptor in scope.collectDescriptorsFiltered(filterToUse, nameFilter, changeNamesForAliased = true)) {
            if (descriptor is ClassDescriptor) {
                if (descriptor.modality == Modality.ABSTRACT || descriptor.modality == Modality.SEALED) continue
                if (!constructorFilter(descriptor)) continue
                descriptor.constructors.filterTo(this) { kindFilter.accepts(it) }
            } else if (!classesOnly && kindFilter.accepts(descriptor)) {
                this.add(descriptor)
            }
        }
    }

    private fun MutableSet<DeclarationDescriptor>.addScopeAndSyntheticExtensions(
        scope: LexicalScope,
        receiverTypes: Collection<KotlinType>,
        callType: CallType<*>,
        kindFilter: DescriptorKindFilter,
        nameFilter: (Name) -> Boolean
    ) {
        if (kindFilter.excludes.contains(DescriptorKindExclude.Extensions)) return
        if (receiverTypes.isEmpty()) return

        fun process(extensionOrSyntheticMember: CallableDescriptor) {
            if (kindFilter.accepts(extensionOrSyntheticMember) && nameFilter(extensionOrSyntheticMember.name)) {
                if (extensionOrSyntheticMember.isExtension) {
                    addAll(extensionOrSyntheticMember.substituteExtensionIfCallable(receiverTypes, callType))
                } else {
                    add(extensionOrSyntheticMember)
                }
            }
        }

        for (descriptor in scope.collectDescriptorsFiltered(
            kindFilter exclude DescriptorKindExclude.NonExtensions,
            nameFilter,
            changeNamesForAliased = true
        )) {
            process(descriptor as CallableDescriptor)
        }

        val syntheticScopes = resolutionFacade.getFrontendService(SyntheticScopes::class.java)
        if (kindFilter.acceptsKinds(DescriptorKindFilter.VARIABLES_MASK)) {
            val lookupLocation =
                (scope.ownerDescriptor.toSourceElement.getPsi() as? KtElement)?.let { KotlinLookupLocation(it) }
                    ?: NoLookupLocation.FROM_IDE

            for (extension in syntheticScopes.collectSyntheticExtensionProperties(receiverTypes, lookupLocation)) {
                process(extension)
            }
        }

        if (kindFilter.acceptsKinds(DescriptorKindFilter.FUNCTIONS_MASK)) {
            for (syntheticMember in syntheticScopes.collectSyntheticMemberFunctions(receiverTypes)) {
                process(syntheticMember)
            }
        }
    }

    private fun <TDescriptor : DeclarationDescriptor> filterOutJavaGettersAndSetters(variants: Collection<TDescriptor>): Collection<TDescriptor> {
        val accessorMethodsToRemove = HashSet<FunctionDescriptor>()
        val filteredVariants = variants.filter { it !is SyntheticJavaPropertyDescriptor }

        for (variant in filteredVariants) {
            if (variant is SyntheticJavaPropertyDescriptor) {
                accessorMethodsToRemove.add(variant.getMethod.original)

                val setter = variant.setMethod
                if (setter != null && setter.returnType?.isUnit() == true) { // we do not filter out non-Unit setters
                    accessorMethodsToRemove.add(setter.original)
                }
            }
        }

        return filteredVariants.filter { it !is FunctionDescriptor || it.original !in accessorMethodsToRemove }
    }

    private fun excludeNonInitializedVariable(
        variants: Collection<DeclarationDescriptor>,
        contextElement: PsiElement
    ): Collection<DeclarationDescriptor> {
        for (element in contextElement.parentsWithSelf) {
            val parent = element.parent
            if (parent is KtVariableDeclaration && element == parent.initializer) {
                return variants.filter { it.findPsi() != parent }
            }
            if (element is KtDeclaration) break // we can use variable inside lambda or anonymous object located in its initializer
        }
        return variants
    }
}

private fun MemberScope.collectStaticMembers(
    resolutionFacade: ResolutionFacade,
    kindFilter: DescriptorKindFilter,
    nameFilter: (Name) -> Boolean
): Collection<DeclarationDescriptor> {
    return getDescriptorsFiltered(kindFilter, nameFilter) + collectSyntheticStaticMembersAndConstructors(
        resolutionFacade,
        kindFilter,
        nameFilter
    )
}

fun ResolutionScope.collectSyntheticStaticMembersAndConstructors(
    resolutionFacade: ResolutionFacade,
    kindFilter: DescriptorKindFilter,
    nameFilter: (Name) -> Boolean
): List<FunctionDescriptor> {
    val syntheticScopes = resolutionFacade.getFrontendService(SyntheticScopes::class.java)
    return (syntheticScopes.collectSyntheticStaticFunctions(this) + syntheticScopes.collectSyntheticConstructors(this))
        .filter { kindFilter.accepts(it) && nameFilter(it.name) }
}

private inline fun <reified T : Any> ResolutionFacade.frontendService(): T
        = this.getFrontendService(T::class.java)
