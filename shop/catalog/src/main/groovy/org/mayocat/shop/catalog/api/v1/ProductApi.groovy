/**
 * Copyright (c) 2012, Mayocat <hello@mayocat.org>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.mayocat.shop.catalog.api.v1

import com.google.common.base.Optional
import com.google.common.base.Strings
import com.sun.jersey.core.header.FormDataContentDisposition
import com.sun.jersey.multipart.FormDataParam
import com.yammer.metrics.annotation.Timed
import groovy.transform.CompileStatic
import org.apache.commons.lang3.StringUtils
import org.mayocat.Slugifier
import org.mayocat.attachment.util.AttachmentUtils
import org.mayocat.authorization.annotation.Authorized
import org.mayocat.configuration.PlatformSettings
import org.mayocat.context.WebContext
import org.mayocat.image.model.Image
import org.mayocat.image.model.Thumbnail
import org.mayocat.image.store.ThumbnailStore
import org.mayocat.model.Attachment
import org.mayocat.rest.Resource
import org.mayocat.rest.annotation.ExistingTenant
import org.mayocat.rest.api.object.ImageApiObject
import org.mayocat.rest.api.object.LinkApiObject
import org.mayocat.rest.api.object.Pagination
import org.mayocat.rest.api.delegate.AttachmentApiDelegate
import org.mayocat.shop.catalog.api.v1.object.*
import org.mayocat.shop.catalog.configuration.shop.CatalogSettings
import org.mayocat.shop.catalog.model.Feature
import org.mayocat.shop.catalog.model.Product
import org.mayocat.shop.catalog.store.CollectionStore
import org.mayocat.shop.catalog.store.ProductStore
import org.mayocat.store.*
import org.mayocat.theme.FeatureDefinition
import org.mayocat.theme.ThemeDefinition
import org.mayocat.theme.TypeDefinition
import org.slf4j.Logger
import org.xwiki.component.annotation.Component
import org.xwiki.component.phase.Initializable

import javax.inject.Inject
import javax.inject.Provider
import javax.ws.rs.*
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

@Component("/api/products")
@Path("/api/products")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ExistingTenant
@CompileStatic
class ProductApi implements Resource, Initializable
{
    @Inject
    Provider<ProductStore> productStore

    @Inject
    Provider<CollectionStore> collectionStore

    @Inject
    Provider<ThumbnailStore> thumbnailStore

    @Inject
    WebContext webContext

    @Inject
    CatalogSettings catalogSettings

    @Inject
    PlatformSettings platformSettings

    @Inject
    Provider<AttachmentStore> attachmentStore

    @Inject
    Slugifier slugifier

    @Inject
    Logger logger

    @Delegate
    AttachmentApiDelegate attachmentApi

    void initialize() {
        attachmentApi = new AttachmentApiDelegate([
                attachmentStore: attachmentStore,
                slugifier: slugifier
        ])
    }

    @GET
    @Timed
    @Authorized
    def getProducts(@QueryParam("number") @DefaultValue("50") Integer number,
                    @QueryParam("offset") @DefaultValue("0") Integer offset,
                    @QueryParam("filter") @DefaultValue("") String filter,
                    @QueryParam("titleMatches") @DefaultValue("") String titleMatches)
    {
        List<ProductApiObject> productList = [];
        def products;
        def totalItems;

        if (filter.equals("uncategorized")) {
            products = this.productStore.get().findOrphanProducts();
            totalItems = products.size()
        } else if (!Strings.isNullOrEmpty(titleMatches)) {
            products = productStore.get().findAllWithTitleLike(titleMatches, number, offset)
            totalItems = productStore.get().countAllWithTitleLike(titleMatches);
        } else {
            products = productStore.get().findAllNotVariants(number, offset)
            totalItems = productStore.get().countAllNotVariants()
        }

        def imageIds = products.collect({ Product product -> product.getFeaturedImageId() })
                                      .findAll({ UUID id -> id != null})

        List<Image> images;
        if (imageIds.size() > 0) {
            List<Attachment> attachments = this.attachmentStore.get().findByIds(imageIds.toList());
            List<Thumbnail> thumbnails = this.thumbnailStore.get().findAllForIds(imageIds.toList());
            images = attachments.collect({ Attachment attachment ->
                def thumbs = thumbnails.findAll({ Thumbnail thumbnail -> thumbnail.attachmentId = attachment.id })
                return new Image(attachment, thumbs.toList())
            });
        } else {
            images = []
        }

        products.each({ Product product ->
            def productApiObject = new ProductApiObject([
                    _href: "/api/products/${product.slug}"
            ])
            productApiObject.withProduct(product)

            if (product.addons.isLoaded()) {
                productApiObject.withAddons(product.addons.get())
            }

            def featuredImage = images.find({ Image image -> image.attachment.id == product.featuredImageId })

            if (featuredImage) {
                productApiObject.withEmbeddedFeaturedImage(featuredImage)
            }

            productList << productApiObject
        })

        def productListResult = new ProductListApiObject([
                pagination: new Pagination([
                    numberOfItems: number,
                    returnedItems: productList.size(),
                    offset: offset,
                    totalItems: totalItems,
                    urlTemplate: '/api/products?number=${numberOfItems}&offset=${offset}&titleMatches=${titleMatches}&filter=${filter}',
                    urlArguments: [
                            titleMatches: titleMatches,
                            filter: filter
                    ]
                ]),
                products: productList
        ])

        productListResult
    }

    @Path("{slug}")
    @GET
    @Timed
    def getProduct(@PathParam("slug") String slug)
    {
        def product = this.productStore.get().findBySlug(slug);
        if (product == null) {
            return Response.status(404).build();
        }

        def collections = this.collectionStore.get().findAllForProduct(product);
        def images = this.getImages(slug)

        def productApiObject = new ProductApiObject([
            _href: "/api/products/${slug}",
            _links: [
                self: new LinkApiObject([ href: "/api/products/${slug}" ]),
                images: new LinkApiObject([ href: "/api/products/${slug}/images" ])
            ]
        ])

        productApiObject.withProduct(product)
        productApiObject.withCollectionRelationships(collections)
        productApiObject.withEmbeddedImages(images)

        if (product.addons.isLoaded()) {
            productApiObject.withAddons(product.addons.get())
        }

        if (product.type.isPresent()) {
            // TODO: have a variants link in _links
            productApiObject.withEmbeddedVariants(productStore.get().findVariants(product))
            productApiObject._links.variants = new LinkApiObject([ href: "/api/products/${slug}/variants" ])
        }

        productApiObject;
    }

    @Path("{slug}/images")
    @GET
    def getImages(@PathParam("slug") String slug)
    {
        def images = [];
        def product = this.productStore.get().findBySlug(slug);
        if (product == null) {
            throw new WebApplicationException(Response.status(404).build());
        }

        for (Attachment attachment : this.attachmentStore.get().findAllChildrenOf(product,
                ["png", "jpg", "jpeg", "gif"] as List))
        {
            def thumbnails = thumbnailStore.get().findAll(attachment);
            def image = new Image(attachment, thumbnails);

            def imageApiObject = new ImageApiObject()
            imageApiObject.withImage(image)

            if (product.featuredImageId != null && product.featuredImageId.equals(attachment.id)) {
                imageApiObject.featured = true
            }

            images << imageApiObject
        }

        images;
    }

    @Path("{slug}/images/{imageSlug}")
    @Authorized
    @DELETE
    @Consumes(MediaType.WILDCARD)
    def detachImage(@PathParam("slug") String slug,
                    @PathParam("imageSlug") String imageSlug)
    {
        def attachment = attachmentStore.get().findBySlug(imageSlug);
        if (attachment == null) {
            return Response.status(404).build();
        }
        try {
            attachmentStore.get().detach(attachment);
            return Response.noContent().build();
        } catch (EntityDoesNotExistException e) {
            return Response.status(404).build();
        }
    }

    @Path("{slug}/images/{imageSlug}")
    @Authorized
    @POST
    @Consumes(MediaType.WILDCARD)
    def updateImage(@PathParam("slug") String slug,
                    @PathParam("imageSlug") String imageSlug, ImageApiObject image)
    {
        def attachment = attachmentStore.get().findBySlug(imageSlug);
        if (attachment == null) {
            return Response.status(404).build();
        }
        try {
            attachment.with {
                setTitle image.title
                setDescription image.description
                setLocalizedVersions image._localized
            }
            attachmentStore.get().update(attachment);
            return Response.noContent().build();
        } catch (EntityDoesNotExistException e) {
            return Response.status(404).build();
        } catch (InvalidEntityException e) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
    }


    @Path("{slug}/attachments")
    @Authorized
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    def addAttachment(@PathParam("slug") String slug,
                      @FormDataParam("file") InputStream uploadedInputStream,
                      @FormDataParam("file") FormDataContentDisposition fileDetail,
                      @FormDataParam("filename") String sentFilename,
                      @FormDataParam("title") String title,
                      @FormDataParam("description") String description)
    {
        def product = this.productStore.get().findBySlug(slug);
        if (product == null) {
            return Response.status(404).build();
        }

        def filename = StringUtils.defaultIfBlank(fileDetail.fileName, sentFilename) as String;
        def created = this.addAttachment(uploadedInputStream, filename, title, description,
                Optional.of(product.id));

        if (product.featuredImageId == null && AttachmentUtils.isImage(filename) && created != null) {

            // If this is an image and the product doesn't have a featured image yet, and the attachment was
            // successful, the we set this image as featured image.
            product.featuredImageId = created.id;

            try {
                this.productStore.get().update(product);
            } catch (EntityDoesNotExistException | InvalidEntityException e) {
                // Fail silently. The attachment has been added successfully, that's what matter
                this.logger.warn("Failed to set first image as featured image for entity {} with id", product.getId());
            }
        }

        return Response.noContent().build();
    }

    @Path("{slug}/move")
    @POST
    @Timed
    @Authorized
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.WILDCARD)
    def move(@PathParam("slug") String slug,
             @FormParam("before") String slugOfProductToMoveBeforeOf,
             @FormParam("after") String slugOfProductToMoveAfterTo)
    {
        try {
            if (!Strings.isNullOrEmpty(slugOfProductToMoveAfterTo)) {
                this.productStore.get().moveProduct(slug, slugOfProductToMoveAfterTo,
                        HasOrderedCollections.RelativePosition.AFTER);
            } else {
                this.productStore.get().moveProduct(slug, slugOfProductToMoveBeforeOf,
                        HasOrderedCollections.RelativePosition.BEFORE);
            }

            return Response.noContent().build();
        } catch (InvalidMoveOperation e) {
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity("Invalid move operation").type(MediaType.TEXT_PLAIN_TYPE).build());
        }
    }

    @Path("{slug}")
    @POST
    @Timed
    @Authorized
    def updateProduct(@PathParam("slug") String slug, ProductApiObject productApiObject)
    {
        try {
            Product product = this.productStore.get().findBySlug(slug);
            if (product == null) {
                return Response.status(404).build();
            } else {
                def id = product.id
                product = productApiObject.toProduct(platformSettings,
                        Optional.<ThemeDefinition> fromNullable(webContext.theme?.definition))
                // ID and slugs are not update-able
                product.id = id
                product.slug = slug

                // Check if virtual flag must be removed or set
                if (Strings.isNullOrEmpty(productApiObject.type) && product.virtual) {
                    // It had a type but no it hasn't
                    product.virtual = false;
                } else if (!Strings.isNullOrEmpty(productApiObject.type) && !product.virtual) {
                    product.virtual = true;
                }

                if (productApiObject._embedded && productApiObject._embedded.get("featuredImage")) {

                    // FIXME:
                    // This should be done via the {slug}/images/ API instead

                    ImageApiObject featured = productApiObject._embedded.get("featuredImage") as ImageApiObject

                    Attachment featuredImage =
                        this.attachmentStore.get().findBySlugAndExtension(featured.slug, featured.file.extension);

                    if (featuredImage) {
                        product.featuredImageId = featuredImage.id
                    }
                }

                this.productStore.get().update(product);

                // Update variants order if needed

                if (productApiObject._embedded?.containsKey("variants")) {
                    List<ProductApiObject> variantApiObjects =
                        productApiObject._embedded?.get("variants") as List<ProductApiObject>

                    List<Product> existingVariants = productStore.get().findVariants(product);

                    variantApiObjects.eachWithIndex({ Map<String, Object> entry, int i ->
                        def index = existingVariants.findIndexOf ({ Product p -> p.slug == entry.slug })
                        if (index != i) {
                            Product variant = existingVariants.find ({ Product p -> p.slug == entry.slug })
                            productStore.get().updatePosition(i, variant);
                        }
                    })

                }
            }

            return Response.ok().build();
        } catch (InvalidEntityException e) {
            throw new com.yammer.dropwizard.validation.InvalidEntityException(e.getMessage(), e.getErrors());
        }
    }

    @Path("{slug}")
    @DELETE
    @Authorized
    @Consumes(MediaType.WILDCARD)
    def deleteProduct(@PathParam("slug") String slug)
    {
        try {
            def product = this.productStore.get().findBySlug(slug);

            if (product == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("No product with this slug could be found\n").type(MediaType.TEXT_PLAIN_TYPE).build();
            }

            this.productStore.get().delete(product);

            return Response.noContent().build();
        } catch (EntityDoesNotExistException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("No product with this slug could be found\n").type(MediaType.TEXT_PLAIN_TYPE).build();
        }
    }

    @Path("{slug}")
    @PUT
    @Timed
    @Authorized
    def replaceProduct(@PathParam("slug") String slug, Product newProduct)
    {
        // TODO
        throw new RuntimeException("Not implemented");
    }

    @POST
    @Timed
    @Authorized
    def createProduct(ProductApiObject productApiObject)
    {
        try {
            def product = productApiObject.toProduct(platformSettings,
                    Optional.<ThemeDefinition> fromNullable(webContext.theme?.definition))

            if (Strings.isNullOrEmpty(product.slug)) {
                product.slug = slugifier.slugify product.title
            }

            def created = productStore.get().create(product);

            // Respond with a created URI relative to this API URL.
            // This will add a location header like http://host/api/<version>/product/my-created-product
            return Response.created(new URI(created.slug)).build();
        }

        catch (InvalidEntityException e) {
            throw new com.yammer.dropwizard.validation.InvalidEntityException(e.getMessage(), e.getErrors());
        } catch (EntityAlreadyExistsException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("A product with this slug already exists\n").type(MediaType.TEXT_PLAIN_TYPE).build();
        } catch (URISyntaxException e) {
            throw new WebApplicationException(e);
        }
    }

    @GET
    @Path("{slug}/variants")
    def getProductVariants(@PathParam("slug") String slug)
    {
        def product = productStore.get().findBySlug(slug)
        if (!product) {
            return Response.status(Response.Status.NOT_FOUND).build()
        }

        def variants = productStore.get().findVariants(product)

        List<ProductApiObject> variantApiObjects = []

        variants.each({ Product variant ->
            ProductApiObject object = new ProductApiObject([
                    _href: "/api/products/${product.slug}/variants/${variant.slug}"
            ])
            object.withProduct(variant)
            if (variant.addons.isLoaded()) {
                object.withAddons(variant.addons.get())
            }
            variantApiObjects << object
        })

        variantApiObjects
    }

    @POST
    @Authorized
    @Path("{slug}/variants")
    def createVariant(@PathParam("slug") String slug, VariantApiObject variantApiObject)
    {
        def product = productStore.get().findBySlug(slug)
        if (!product) {
            return Response.status(Response.Status.NOT_FOUND).build()
        }

        if (!product.getType().isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity([reason: "Cannot create a variant for a product which type is not defined"]).build()
        }

        String type = product.type.get()

        TypeDefinition typeDefinition
        def platformTypes = catalogSettings.productsSettings.types
        def themeTypes = webContext.theme?.definition?.productTypes

        if (platformTypes && platformTypes.containsKey(type)) {
            typeDefinition = platformTypes[type]
        } else if (themeTypes && themeTypes.containsKey(type)) {
            typeDefinition = themeTypes[type]
        }

        if (!typeDefinition) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity([reason: """Type definition for [${type}] not found"""]).build()
        }

        List<Feature> variantFeatures = [];

        for (feature in variantApiObject.features.entrySet()) {

            // 1. Check if the feature is valid, according to the type definition

            if (!typeDefinition.features.containsKey(feature.key)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity([reason: "Feature [${feature.key}] is not allowed for type [${type}]"]).build()
            }

            FeatureDefinition variant = typeDefinition.features.get(feature.key);

            if (variant.keys.size() > 0 && !variant.keys.containsKey(feature.value)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity([reason: "Value [${feature.value}] is not allowed for variant [${feature.key}]"])
                        .build()
            }

            // 2. Check if feature exists, and create it if not

            String title = variant.keys.containsKey(feature.value) ?
                variant.keys.get(feature.value) : feature.value
            String featureSlug = variant.keys.containsKey(feature.value) ? feature.value : slugifier.slugify(title)

            def feat = productStore.get().findFeature(product, feature.key, featureSlug);
            if (!feat) {
                // It does not exist, we create it
                def featureToCreate = new Feature();

                featureToCreate.with {
                    setParentId(product.id)
                    setSlug(feature.key + "-" + featureSlug)
                    setFeature(feature.key)
                    setFeatureSlug(featureSlug)
                    setTitle(title)
                }

                featureToCreate = productStore.get().createFeature(featureToCreate);
                variantFeatures << featureToCreate
            }
            else {
                variantFeatures << feat
            }
        }

        // Check if product is virtual, update if it is not

        if (!product.virtual) {
            product.virtual = true;
            productStore.get().update(product);
        }

        // Create variant

        def productVariant = new Product()
        def variantSlug = variantFeatures.collect({ Feature f -> f.featureSlug }).join("-")
        productVariant.with {
            setParentId(product.id)
            setFeatures(variantFeatures.collect({ Feature f -> f.id }))
            setSlug(variantSlug)
            setTitle(variantFeatures.collect({ Feature f -> f.title }).join(" - "))
        }

        try {
            Product created = productStore.get().create(productVariant)

            if (typeDefinition.getVariants().properties.indexOf("stock") >= 0) {
                List<Product> products = this.productStore.get().findVariants(product);
                if (!products.any({Product v -> v.stock && v.stock > 0 })) {
                    product.stock = 0
                    productStore.get().update(product)
                }
                else {
                    product.stock = null
                    productStore.get().update(product)
                }
            }

            return Response.created(new URI(created.slug)).build();
        }
        catch (EntityAlreadyExistsException e) {
            return Response.status(Response.Status.CONFLICT).entity([reason: "Variant already exists"]).build()
        }

    }

    @POST
    @Authorized
    @Path("{slug}/variants/{variantSlug}")
    def updateVariant(@PathParam("slug") String slug, @PathParam("variantSlug") String variantSlug,
            VariantApiObject variantApiObject)
    {
        try {
            Product product = this.productStore.get().findBySlug(slug);
            if (product == null || !product.type.isPresent()) {
                return Response.status(404).build();
            } else {

                TypeDefinition typeDefinition = getTypeDefinition(product)

                if (!typeDefinition) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity([reason: """Type definition for [${product.type}] not found"""]).build()
                }

                Product variant = productStore.get().findVariant(product, variantSlug);

                if (variant == null) {
                    return Response.status(404).build();
                } else {
                    def id = variant.id
                    variant = variantApiObject.toProduct(platformSettings,
                            Optional.<ThemeDefinition> fromNullable(webContext.theme?.definition), Optional.of(product))
                    // ID and slugs are not update-able
                    variant.id = id
                    variant.slug = variantSlug
                    variant.parentId = product.id

                    this.productStore.get().update(variant)

                    if (typeDefinition.getVariants().properties.indexOf("stock") >= 0) {
                        List<Product> products = this.productStore.get().findVariants(product);
                        if (!products.any({Product v -> v.stock && v.stock > 0 })) {
                            product.stock = 0
                            productStore.get().update(product)
                        }
                        else {
                            product.stock = null
                            productStore.get().update(product)
                        }
                    }
                }
            }

            return Response.ok().build();
        } catch (InvalidEntityException e) {
            throw new com.yammer.dropwizard.validation.InvalidEntityException(e.getMessage(), e.getErrors());
        }
    }

    @DELETE
    @Authorized
    @Path("{slug}/variants/{variantSlug}")
    @Consumes(MediaType.WILDCARD)
    def deleteVariant(@PathParam("slug") String slug, @PathParam("variantSlug") String variantSlug)
    {
        Product product = this.productStore.get().findBySlug(slug);
        if (product == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("No product with this slug could be found\n").type(MediaType.TEXT_PLAIN_TYPE).build();
        } else {

            Product variant = productStore.get().findVariant(product, variantSlug);

            if (variant == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("No variant with this slug could be found\n").type(MediaType.TEXT_PLAIN_TYPE).build();
            }

            this.productStore.get().delete(variant);

            return Response.noContent().build();
        }
    }

    private def TypeDefinition getTypeDefinition(Product product)
    {
        TypeDefinition typeDefinition
        def platformTypes = catalogSettings.productsSettings.types
        def themeTypes = webContext.theme?.definition?.productTypes

        if (platformTypes && platformTypes.containsKey(product.type.get())) {
            typeDefinition = platformTypes[product.type.get()]
        } else if (themeTypes && themeTypes.containsKey(product.type.get())) {
            typeDefinition = themeTypes[product.type.get()]
        }
        typeDefinition
    }

}
