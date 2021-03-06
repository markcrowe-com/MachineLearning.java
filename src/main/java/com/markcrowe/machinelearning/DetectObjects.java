import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.tensorflow.SavedModelBundle;
import org.tensorflow.Tensor;
import org.tensorflow.proto.framework.MetaGraphDef;
import org.tensorflow.proto.framework.SignatureDef;
import org.tensorflow.proto.framework.TensorInfo;
import org.tensorflow.types.UInt8;

/**
 * Java inference for the Object Detection API at:
 * https://github.com/tensorflow/models/tree/75c931fd91b4806e4d28f816ab5d84ed35423570/samples/languages/java/object_detection
 * https://github.com/tensorflow/models/blob/master/research/object_detection/
 */
public class DetectObjects
{
	public static void main(String[] args) throws Exception
	{
		if(args.length < 3)
		{
			printUsage(System.err);
			System.exit(1);
		}
		final String[] labels = loadLabels(args[1]);
		try( SavedModelBundle model = SavedModelBundle.load(args[0], "serve"))
		{
			printSignature(model);
			for(int arg = 2; arg < args.length; arg++)
			{
				final String filename = args[arg];
				List<Tensor<?>> outputs = null;
				try( Tensor<UInt8> input = makeImageTensor(filename))
				{
					outputs
							= model
									.session()
									.runner()
									.feed("image_tensor", input)
									.fetch("detection_scores")
									.fetch("detection_classes")
									.fetch("detection_boxes")
									.run();
				}
				try( Tensor<Float> scoresT = outputs.get(0).expect(Float.class);  Tensor<Float> classesT = outputs.get(1).expect(Float.class);  Tensor<Float> boxesT = outputs.get(2).expect(Float.class))
				{
					// All these tensors have:
					// - 1 as the first dimension
					// - maxObjects as the second dimension
					// While boxesT will have 4 as the third dimension (2 sets of (x, y) coordinates).
					// This can be verified by looking at scoresT.shape() etc.
					int maxObjects = (int) scoresT.shape()[1];
					float[] scores = scoresT.copyTo(new float[1][maxObjects])[0];
					float[] classes = classesT.copyTo(new float[1][maxObjects])[0];
					float[][] boxes = boxesT.copyTo(new float[1][maxObjects][4])[0];
					// Print all objects whose score is at least 0.5.
					System.out.printf("* %s\n", filename);
					boolean foundSomething = false;
					for(int i = 0; i < scores.length; ++i)
					{
						if(scores[i] < 0.5)
						{
							continue;
						}
						foundSomething = true;
						System.out.printf("\tFound %-20s (score: %.4f)\n", labels[(int) classes[i]], scores[i]);
					}
					if(!foundSomething)
					{
						System.out.println("No objects detected with a high enough score.");
					}
				}
			}
		}
	}

	private static void printSignature(SavedModelBundle model) throws Exception
	{
		MetaGraphDef m = MetaGraphDef.parseFrom(model.metaGraphDef());
		SignatureDef sig = m.getSignatureDefOrThrow("serving_default");
		int numInputs = sig.getInputsCount();
		int i = 1;
		System.out.println("MODEL SIGNATURE");
		System.out.println("Inputs:");
		for(Map.Entry<String, TensorInfo> entry : sig.getInputsMap().entrySet())
		{
			TensorInfo t = entry.getValue();
			System.out.printf(
					"%d of %d: %-20s (Node name in graph: %-20s, type: %s)\n",
					i++, numInputs, entry.getKey(), t.getName(), t.getDtype());
		}
		int numOutputs = sig.getOutputsCount();
		i = 1;
		System.out.println("Outputs:");
		for(Map.Entry<String, TensorInfo> entry : sig.getOutputsMap().entrySet())
		{
			TensorInfo t = entry.getValue();
			System.out.printf(
					"%d of %d: %-20s (Node name in graph: %-20s, type: %s)\n",
					i++, numOutputs, entry.getKey(), t.getName(), t.getDtype());
		}
		System.out.println("-----------------------------------------------");
	}

	private static String[] loadLabels(String filename) throws Exception
	{

		return new String[]
		{
			"daisy", "dandelion", "roses", "sunflowers", "tulips"
		};
	}

	private static void bgr2rgb(byte[] data)
	{
		for(int i = 0; i < data.length; i += 3)
		{
			byte tmp = data[i];
			data[i] = data[i + 2];
			data[i + 2] = tmp;
		}
	}

	private static Tensor<UInt8> makeImageTensor(String filename) throws IOException
	{
		BufferedImage img = ImageIO.read(new File(filename));
		if(img.getType() != BufferedImage.TYPE_3BYTE_BGR)
		{
			throw new IOException(
					String.format(
							"Expected 3-byte BGR encoding in BufferedImage, found %d (file: %s). This code could be made more robust",
							img.getType(), filename));
		}
		byte[] data = ((DataBufferByte) img.getData().getDataBuffer()).getData();
		// ImageIO.read seems to produce BGR-encoded images, but the model expects RGB.
		bgr2rgb(data);
		final long BATCH_SIZE = 1;
		final long CHANNELS = 3;
		long[] shape = new long[]
		{
			BATCH_SIZE, img.getHeight(), img.getWidth(), CHANNELS
		};
		return Tensor.create(UInt8.class, shape, ByteBuffer.wrap(data));
	}

	private static void printUsage(PrintStream s)
	{
		s.println("USAGE: <model> <label_map> <image> [<image>] [<image>]");
		s.println("");
		s.println("Where");
		s.println("<model> is the path to the SavedModel directory of the model to use.");
		s.println("        For example, the saved_model directory in tarballs from ");
		s.println(
				"        https://github.com/tensorflow/models/blob/master/research/object_detection/g3doc/detection_model_zoo.md)");
		s.println("");
		s.println(
				"<label_map> is the path to a file containing information about the labels detected by the model.");
		s.println("            For example, one of the .pbtxt files from ");
		s.println(
				"            https://github.com/tensorflow/models/tree/master/research/object_detection/data");
		s.println("");
		s.println("<image> is the path to an image file.");
		s.println("        Sample images can be found from the COCO, Kitti, or Open Images dataset.");
		s.println(
				"        See: https://github.com/tensorflow/models/blob/master/research/object_detection/g3doc/detection_model_zoo.md");
	}
}
