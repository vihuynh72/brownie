package io.github.vihuynh72.brownie.api.form;

import java.io.IOException;

/** Brings home the official PDF a catalog entry names. The URL is the catalog's own -- never a person's. */
public interface CatalogFormFetcher {

    /**
     * The form's bytes, fetched under the host allowlist.
     *
     * @throws IOException any failure reaching or reading the site; the caller turns it into a "try again" answer.
     */
    byte[] fetch(FormLibraryCatalog.Entry entry) throws IOException;
}
