import java.io.File;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Release gate: inspect the actual merged manifest rather than only source manifests. */
public final class VerifyReleaseNoInternetPermission {
  private static final String ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android";

  private VerifyReleaseNoInternetPermission() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("Expected the path to the merged release AndroidManifest.xml.");
    }
    File manifest = new File(args[0]);
    if (!manifest.isFile()) {
      throw new IllegalStateException("Release merged manifest not found: " + manifest.getAbsolutePath());
    }

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    Element root = factory.newDocumentBuilder().parse(manifest).getDocumentElement();

    for (String permissionElementName : new String[] {"uses-permission", "uses-permission-sdk-23"}) {
      NodeList permissions = root.getElementsByTagName(permissionElementName);
      for (int index = 0; index < permissions.getLength(); index++) {
        Element permission = (Element) permissions.item(index);
        String name = permission.getAttributeNS(ANDROID_NAMESPACE, "name");
        if ("android.permission.INTERNET".equals(name)) {
          throw new IllegalStateException(
              "Release manifest requests android.permission.INTERNET. Remove it before publishing AnAn Notes.");
        }
      }
    }

    System.out.println("Release permission check passed: android.permission.INTERNET is absent.");
  }
}
