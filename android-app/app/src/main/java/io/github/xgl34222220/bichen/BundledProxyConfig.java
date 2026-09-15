package io.github.xgl34222220.bichen;

import java.io.*;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

/** Sanitized built-in Mihomo template derived from the user-provided TPROXY profile. */
final class BundledProxyConfig {
    static InputStream open() throws IOException {
        try {
            return new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(DATA)));
        } catch (IllegalArgumentException e) {
            throw new IOException("内置代理配置损坏", e);
        }
    }

    private static final String DATA =
        "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" +
        "YrF0GEEmlmIolmI4lmKkIwWdC1Csxhq0HbGVKVQWe5wVZfBNkeI63l5PVshbkiyjaMKqIhhl8Md03EYAAi00iEBke0CE0AStAnT5" +
        "7JGcv8O7UNTBduIOijno8rbHGGDyMfSGWluybQfKGNrNICqFuY8QA+YXSN0h2KhR9c+NKxujiEdtE0rbOYgOshEUXEG8gaQS8jQk" +
        "hO5NXcQQ+WmRdQlqiB61VdTmbqDRpkBd0M+lwYJuD6RowRfi5qJJHH/XStDB7AJg+s+Htv7xue3PN7Y2f2x8+7QL05tN2v92VIEo" +
        "aTTk+tl4gzY8fJGwJTXmG7wVJQ0XIbM87C4NsMgOcnZ51orZRqRK4EqVXRDIhmeRyq5TA+ChQ8LTNYgsMW8QJ3z0LUOwtkrsanX5" +
        "uygye3EzM0RDVP9Ca9qWjk722DrfROIft67eTdQ3fqxt3GvceLD1/IuBPAQkS4uHYVg4d82WbBthqRJdhqCz+aH0yODAPqTjBWci" +
        "R22R7+eAuyhB62pBlR0bsXE5gFjHwO8yRrJR5m0LxxQi0DAQVNP1y9cdmAPejMaudtxZvfCWM0bTToqM3qo2W5FOOFR7xZGn0ZQJ" +
        "kTnIwviLriouQVvzkyV7yfnEruYS8K5TviVEJ2agC1cC1gwekqViC6fmsnpzmuNEw4ldu38l7dndePa1tf3uma27D62t59dqF763" +
        "as9+aFy9a9XufNrY/BAuX9U3rlhw2/jzZ/RSu3nG2rr6sPb+dWvrzlfbn56z6n87u33+fat24V5945ZV33hS23hivXh0uf7jl1bj" +
        "4gXoa1qZXe75eUpSe/jXxrXvrK2z1xr3nwL3O407P0Hi7dqVj62tB9/Xrly3ts983rh126qd+YP969O/1v/ylQUqQ2vZ/urD7bMf" +
        "Wds/vLd196xVv/pd7fFzq37hw8bnv7dqH35ng/jkj/X7UNndB/VPr1ovNp9TfvX3P2h8/QQgPa5d+LNVO3dl68FjinNrc/PFo48o" +
        "xdDJZHrdGrG/R+3vMft7r/29def59id3rPq1u/X3gO/HnzW+uwzV3K/fh7pvPKhfvwecNrbOnLNql/5YO3fbWsrN0M+0lT+anzxs" +
        "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" +
        "6EV/frAjj0zmpw5yLS9R/j92Bdni0nMAAA==";
}
