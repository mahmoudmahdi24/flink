/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.spargel.java;


import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.Validate;
import org.apache.flink.api.common.aggregators.Aggregator;
import org.apache.flink.api.common.functions.JoinFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.RichCoGroupFunction;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.operators.CoGroupOperator;
import org.apache.flink.api.java.operators.CustomUnaryOperation;
import org.apache.flink.api.java.operators.DeltaIteration;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.tuple.Tuple4;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.api.java.typeutils.TupleTypeInfo;
import org.apache.flink.api.java.typeutils.TypeExtractor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.spargel.java.multicast.MessageWithHeader;
import org.apache.flink.util.Collector;

/**
 * This class represents iterative graph computations, programmed in a
 * vertex-centric perspective. It is a special case of <i>Bulk Synchronous
 * Parallel<i> computation. The paradigm has also been implemented by Google's
 * <i>Pregel</i> system and by <i>Apache Giraph</i>.
 * <p>
 * Vertex centric algorithms operate on graphs, which are defined through
 * vertices and edges. The algorithms send messages along the edges and update
 * the state of vertices based on the old state and the incoming messages. All
 * vertices have an initial state. The computation terminates once no vertex
 * updates it state any more. Additionally, a maximum number of iterations
 * (supersteps) may be specified.
 * <p>
 * The computation is here represented by two functions:
 * <ul>
 * <li>The {@link VertexUpdateFunction} receives incoming messages and may
 * updates the state for the vertex. If a state is updated, messages are sent
 * from this vertex. Initially, all vertices are considered updated.</li>
 * <li>The {@link MessagingFunction} takes the new vertex state and sends
 * messages along the outgoing edges of the vertex. The outgoing edges may
 * optionally have an associated value, such as a weight.</li>
 * </ul>
 * <p>
 * Vertex-centric graph iterations are instantiated by the
 * {@link #withPlainEdges(DataSet, VertexUpdateFunction, MessagingFunction, int)}
 * method, or the
 * {@link #withValuedEdges(DataSet, VertexUpdateFunction, MessagingFunction, int)}
 * method, depending on whether the graph's edges are carrying values.
 *
 * @param <VertexKey>
 *            The type of the vertex key (the vertex identifier).
 * @param <VertexValue>
 *            The type of the vertex value (the state of the vertex).
 * @param <Message>
 *            The type of the message sent between vertices along the edges.
 * @param <EdgeValue>
 *            The type of the values that are associated with the edges.
 */
public class VertexCentricIteration2<VertexKey extends Comparable<VertexKey>, VertexValue, Message, EdgeValue>
		implements
		CustomUnaryOperation<Tuple2<VertexKey, VertexValue>, Tuple2<VertexKey, VertexValue>> {
	
	public static final String HASH_KEYS_BROADCAST_SET = "HASH_KEYS_BROADCAST_SET";
	
	private final VertexUpdateFunction<VertexKey, VertexValue, Message> updateFunction;

	private final MessagingFunction2<VertexKey, VertexValue, Message, EdgeValue> messagingFunction;

	private final DataSet<Tuple2<VertexKey, VertexKey>> edgesWithoutValue;

	private final DataSet<Tuple3<VertexKey, VertexKey, EdgeValue>> edgesWithValue;

	private final Map<String, Aggregator<?>> aggregators;

	private final int maximumNumberOfIterations;

	private final List<Tuple2<String, DataSet<?>>> bcVarsUpdate = new ArrayList<Tuple2<String, DataSet<?>>>(
			4);

	private final List<Tuple2<String, DataSet<?>>> bcVarsMessaging = new ArrayList<Tuple2<String, DataSet<?>>>(
			4);

	@SuppressWarnings("rawtypes")
	private final TypeInformation<MessageWithHeader> packedMessageType;
	private final TypeInformation<Message> unPackedMessageType;
	private final TypeInformation<VertexKey> keyType;
	
	private DataSet<Tuple2<VertexKey, VertexValue>> initialVertices;

	private String name;

	private int parallelism = -1;

	private boolean unmanagedSolutionSet;

	// ----------------------------------------------------------------------------------

	private VertexCentricIteration2(
			VertexUpdateFunction<VertexKey, VertexValue, Message> uf,
			MessagingFunction2<VertexKey, VertexValue, Message, EdgeValue> mf,
			DataSet<Tuple2<VertexKey, VertexKey>> edgesWithoutValue,
			int maximumNumberOfIterations) {
		Validate.notNull(uf);
		Validate.notNull(mf);
		Validate.notNull(edgesWithoutValue);
		Validate.isTrue(maximumNumberOfIterations > 0,
				"The maximum number of iterations must be at least one.");

		// check that the edges are actually a valid tuple set of vertex key
		// types
		TypeInformation<Tuple2<VertexKey, VertexKey>> edgesType = edgesWithoutValue
				.getType();
		Validate.isTrue(edgesType.isTupleType() && edgesType.getArity() == 2,
				"The edges data set (for edges without edge values) must consist of 2-tuples.");

		TupleTypeInfo<?> tupleInfo = (TupleTypeInfo<?>) edgesType;
		Validate.isTrue(
				tupleInfo.getTypeAt(0).equals(tupleInfo.getTypeAt(1))
						&& Comparable.class.isAssignableFrom(tupleInfo
								.getTypeAt(0).getTypeClass()),
				"Both tuple fields (source and target vertex id) must be of the data type that represents the vertex key and implement the java.lang.Comparable interface.");

		this.updateFunction = uf;
		this.messagingFunction = mf;
		this.edgesWithoutValue = edgesWithoutValue;
		this.edgesWithValue = null;
		this.maximumNumberOfIterations = maximumNumberOfIterations;
		this.aggregators = new HashMap<String, Aggregator<?>>();
		
		this.keyType = TypeExtractor.createTypeInfo(
				MessagingFunction2.class, mf.getClass(), 0, null, null);
		this.unPackedMessageType = TypeExtractor.createTypeInfo(
				MessagingFunction2.class, mf.getClass(), 2, null, null);
		this.packedMessageType = MessageWithHeader.getTypeInfo(this.keyType, this.unPackedMessageType);
		// this.messageType = mf.getMessageType();
	}

//	@SuppressWarnings("rawtypes")
//	private TypeInformation<MessageWithHeader> getMessageType(
//			MessagingFunction2<VertexKey, VertexValue, Message, EdgeValue> mf) {
//
//		TypeInformation<VertexKey> keyType = TypeExtractor.createTypeInfo(
//				MessagingFunction2.class, mf.getClass(), 0, null, null);
//		TypeInformation<Message> msgType = TypeExtractor.createTypeInfo(
//				MessagingFunction2.class, mf.getClass(), 2, null, null);
//		return MessageWithHeader.getTypeInfo(keyType, msgType);
//	}

	/**
	 * Registers a new aggregator. Aggregators registered here are available
	 * during the execution of the vertex updates via
	 * {@link VertexUpdateFunction#getIterationAggregator(String)} and
	 * {@link VertexUpdateFunction#getPreviousIterationAggregate(String)}.
	 * 
	 * @param name
	 *            The name of the aggregator, used to retrieve it and its
	 *            aggregates during execution.
	 * @param aggregator
	 *            The aggregator.
	 */
	public void registerAggregator(String name, Aggregator<?> aggregator) {
		this.aggregators.put(name, aggregator);
	}

	/**
	 * Adds a data set as a broadcast set to the messaging function.
	 * 
	 * @param name
	 *            The name under which the broadcast data is available in the
	 *            messaging function.
	 * @param data
	 *            The data set to be broadcasted.
	 */
	public void addBroadcastSetForMessagingFunction(String name, DataSet<?> data) {
		this.bcVarsMessaging.add(new Tuple2<String, DataSet<?>>(name, data));
	}

	/**
	 * Adds a data set as a broadcast set to the vertex update function.
	 * 
	 * @param name
	 *            The name under which the broadcast data is available in the
	 *            vertex update function.
	 * @param data
	 *            The data set to be broadcasted.
	 */
	public void addBroadcastSetForUpdateFunction(String name, DataSet<?> data) {
		this.bcVarsUpdate.add(new Tuple2<String, DataSet<?>>(name, data));
	}

	/**
	 * Sets the name for the vertex-centric iteration. The name is displayed in
	 * logs and messages.
	 * 
	 * @param name
	 *            The name for the iteration.
	 */
	public void setName(String name) {
		this.name = name;
	}

	/**
	 * Gets the name from this vertex-centric iteration.
	 * 
	 * @return The name of the iteration.
	 */
	public String getName() {
		return name;
	}

	/**
	 * Sets the degree of parallelism for the iteration.
	 * 
	 * @param parallelism
	 *            The degree of parallelism.
	 */
	public void setParallelism(int parallelism) {
		Validate.isTrue(parallelism > 0 || parallelism == -1,
				"The degree of parallelism must be positive, or -1 (use default).");
		this.parallelism = parallelism;
	}

	/**
	 * Gets the iteration's degree of parallelism.
	 * 
	 * @return The iterations parallelism, or -1, if not set.
	 */
	public int getParallelism() {
		return parallelism;
	}

	/**
	 * Defines whether the solution set is kept in managed memory (Flink's
	 * internal way of keeping object in serialized form) or as a simple object
	 * map. By default, the solution set runs in managed memory.
	 * 
	 * @param unmanaged
	 *            True, to keep the solution set in unmanaged memory, false
	 *            otherwise.
	 */
	public void setSolutionSetUnmanagedMemory(boolean unmanaged) {
		this.unmanagedSolutionSet = unmanaged;
	}

	/**
	 * Gets whether the solution set is kept in managed memory (Flink's internal
	 * way of keeping object in serialized form) or as a simple object map. By
	 * default, the solution set runs in managed memory.
	 * 
	 * @return True, if the solution set is in unmanaged memory, false
	 *         otherwise.
	 */
	public boolean isSolutionSetUnmanagedMemory() {
		return this.unmanagedSolutionSet;
	}

	// --------------------------------------------------------------------------------------------
	// Custom Operator behavior
	// --------------------------------------------------------------------------------------------

	/**
	 * Sets the input data set for this operator. In the case of this operator
	 * this input data set represents the set of vertices with their initial
	 * state.
	 * 
	 * @param inputData
	 *            The input data set, which in the case of this operator
	 *            represents the set of vertices with their initial state.
	 * 
	 * @see org.apache.flink.api.java.operators.CustomUnaryOperation#setInput(org.apache.flink.api.java.DataSet)
	 */
	@Override
	public void setInput(DataSet<Tuple2<VertexKey, VertexValue>> inputData) {
		// sanity check that we really have two tuples
		TypeInformation<Tuple2<VertexKey, VertexValue>> inputType = inputData
				.getType();
		Validate.isTrue(inputType.isTupleType() && inputType.getArity() == 2,
				"The input data set (the initial vertices) must consist of 2-tuples.");

		// check that the key type here is the same as for the edges
		TypeInformation<VertexKey> keyType = ((TupleTypeInfo<?>) inputType)
				.getTypeAt(0);
		TypeInformation<?> edgeType = edgesWithoutValue != null ? edgesWithoutValue
				.getType() : edgesWithValue.getType();
		TypeInformation<VertexKey> edgeKeyType = ((TupleTypeInfo<?>) edgeType)
				.getTypeAt(0);

		Validate.isTrue(
				keyType.equals(edgeKeyType),
				"The first tuple field (the vertex id) of the input data set (the initial vertices) "
						+ "must be the same data type as the first fields of the edge data set (the source vertex id). "
						+ "Here, the key type for the vertex ids is '%s' and the key type  for the edges is '%s'.",
				keyType, edgeKeyType);

		this.initialVertices = inputData;
	}

	/**
	 * Creates the operator that represents this vertex-centric graph
	 * computation.
	 * 
	 * @return The operator that represents this vertex-centric graph
	 *         computation.
	 */
	@Override
	public DataSet<Tuple2<VertexKey, VertexValue>> createResult() {
		if (this.initialVertices == null) {
			throw new IllegalStateException(
					"The input data set has not been set.");
		}

		// prepare some type information
		TypeInformation<Tuple2<VertexKey, VertexValue>> vertexTypes = initialVertices
				.getType();
		TypeInformation<VertexKey> keyType = ((TupleTypeInfo<?>) initialVertices
				.getType()).getTypeAt(0);
		TypeInformation<Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>>> packedMessageTupleTypeInfo = new TupleTypeInfo<Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>>>(
				keyType, packedMessageType);
		TypeInformation<Tuple2<VertexKey, Message>> unpackedMessageTupleTypeInfo = new TupleTypeInfo<Tuple2<VertexKey, Message>>(
				keyType, unPackedMessageType);

		// set up the iteration operator
		final String name = (this.name != null) ? this.name
				: "Vertex-centric iteration (" + updateFunction + " | "
						+ messagingFunction + ")";
		final int[] zeroKeyPos = new int[] { 0 };

		final DeltaIteration<Tuple2<VertexKey, VertexValue>, Tuple2<VertexKey, VertexValue>> iteration = this.initialVertices
				.iterateDelta(this.initialVertices,
						this.maximumNumberOfIterations, zeroKeyPos);
		iteration.name(name);
		iteration.parallelism(parallelism);
		iteration.setSolutionSetUnManaged(unmanagedSolutionSet);

		// register all aggregators
		for (Map.Entry<String, Aggregator<?>> entry : this.aggregators
				.entrySet()) {
			iteration.registerAggregator(entry.getKey(), entry.getValue());
		}

		// build the messaging function (co group)
		CoGroupOperator<?, ?, Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>>> messages;
		DataSet<Tuple3<VertexKey, VertexKey, Integer>> edgesWithChannelId = null;
		// DataSet<Tuple2<VertexKey, MessageWithSender<VertexKey, Message>>>
		// messages;
		if (edgesWithoutValue != null) {
			MessagingUdfNoEdgeValues<VertexKey, VertexValue, Message> messenger = new MessagingUdfNoEdgeValues<VertexKey, VertexValue, Message>(
					messagingFunction, packedMessageTupleTypeInfo);
			messages = this.edgesWithoutValue.coGroup(iteration.getWorkset())
					.where(0).equalTo(0).with(messenger);
			edgesWithChannelId = edgesWithoutValue
					.partitionByHash(1)
					.map(new SubtaskIndexAdder<VertexKey>());
		} else {
			MessagingUdfWithEdgeValues<VertexKey, VertexValue, Message, EdgeValue> messenger = new MessagingUdfWithEdgeValues<VertexKey, VertexValue, Message, EdgeValue>(
					messagingFunction, packedMessageTupleTypeInfo);
			messages = this.edgesWithValue.coGroup(iteration.getWorkset())
					.where(0).equalTo(0).with(messenger);
			edgesWithChannelId = edgesWithValue
					// the following is essentially a projection, but I think it
					// cannot be done as a projection
					// .project(0,1).types(VertexKey.class, VertexKey.class)
					.map(new MapFunction<Tuple3<VertexKey, VertexKey, EdgeValue>, Tuple2<VertexKey, VertexKey>>() {
						private static final long serialVersionUID = 1L;
						Tuple2<VertexKey, VertexKey> reuse = new Tuple2<VertexKey, VertexKey>();

						@Override
						public Tuple2<VertexKey, VertexKey> map(
								Tuple3<VertexKey, VertexKey, EdgeValue> value)
								throws Exception {
							reuse.f0 = value.f0;
							reuse.f1 = value.f1;
							return reuse;
						}
					})
					.partitionByHash(1)
					.map(new SubtaskIndexAdder<VertexKey>());
		}

		DataSet<Tuple2<Integer, VertexKey>> hashKeys = edgesWithChannelId.
				groupBy(2).min(1).map(new ProjectFields<VertexKey>());
		

		DataSet<Tuple4<VertexKey, VertexKey, Integer, VertexKey>> edges4 = 
				edgesWithChannelId.joinWithTiny(hashKeys).where(2).equalTo(0)
				.with(new DummyJoinFunc<VertexKey>());

		// configure coGroup message function with name and broadcast variables
		messages = messages.name("Messaging");
		for (Tuple2<String, DataSet<?>> e : this.bcVarsMessaging) {
			messages = messages.withBroadcastSet(e.f1, e.f0);
		}


// The join idea: too slow, due to extra unnecessary network activity 		
//		DataSet<Tuple3<Integer, VertexKey, Message>> messages1 = messages
//				.partitionByHash(0)
//				.flatMap(new UnpackPhase1<VertexKey, Message>(this.keyType, this.unPackedMessageType));


//		// edgesWithChannelId.print();
//		// messages1.print();
//		DataSet<Tuple2<VertexKey, Message>> messages2 = edgesWithChannelId
//		// Kell ez?
//		 //.joinWithTiny(messages1)
//			.join(messages1)
//				.where(2, 0)
//				// .equalTo(0)
//				// .equalTo(new SenderSelector<VertexKey, Message>())
//				.equalTo(0, 1)
//				// .equalTo(new ChannelIdAndSenderSelectorMsg<VertexKey,
//				// Message>())
//				.with(new UnpackMessage3<VertexKey, Message>(
//						unpackedMessageTupleTypeInfo));
//				//.projectFirst(1).projectSecond(2).types(VertexKey, Message);

// The flatmap idea: did not work
		//		DataSet<Tuple2<VertexKey, Message>> messages2 = messages
//				.partitionByHash(0)
//				.flatMap(new UnpackMessage<VertexKey, Message>(edgesWithChannelId,
//								unpackedMessageTupleTypeInfo));

		DataSet<Tuple4<Integer, VertexKey, Message, VertexKey>> messages1 = messages
		.partitionByHash(0)
		.flatMap(new UnpackPhase1Bar<VertexKey, Message>(this.keyType, this.unPackedMessageType))
		.withBroadcastSet(hashKeys, HASH_KEYS_BROADCAST_SET);


		DataSet<Tuple2<VertexKey, Message>> messages2 = edges4
				.coGroup(messages1).where(3)
				.equalTo(3)
				.with(new UnpackMessageCoGroup<VertexKey, Message>(
						unpackedMessageTupleTypeInfo));
	
		
		
		VertexUpdateUdf<VertexKey, VertexValue, Message> updateUdf = new VertexUpdateUdf<VertexKey, VertexValue, Message>(
				updateFunction, vertexTypes);
		
		// build the update function (co group)
		CoGroupOperator<?, ?, Tuple2<VertexKey, VertexValue>> updates = messages2
				.coGroup(iteration.getSolutionSet()).where(0).equalTo(0)
				.with(updateUdf);

		// configure coGroup update function with name and broadcast variables
		updates = updates.name("Vertex State Updates");
		for (Tuple2<String, DataSet<?>> e : this.bcVarsUpdate) {
			updates = updates.withBroadcastSet(e.f1, e.f0);
		}

		// let the operator know that we preserve the key field
		updates.withConstantSetFirst("0").withConstantSetSecond("0");

		return iteration.closeWith(updates, updates);

	}


	public static class ProjectFields<VertexKey>
	implements MapFunction<Tuple3<VertexKey,VertexKey,Integer>, Tuple2<Integer, VertexKey>> {
		private static final long serialVersionUID = 1L;
		Tuple2<Integer, VertexKey> reuse = new Tuple2<Integer, VertexKey>();
		@Override
		public Tuple2<Integer, VertexKey> map(
				Tuple3<VertexKey, VertexKey, Integer> value)
				throws Exception {
			reuse.f0 = value.f2;
			reuse.f1 = value.f1;
			return reuse;
		}
	}
	
	public static class DummyJoinFunc<VertexKey>
	implements
	JoinFunction<Tuple3<VertexKey, VertexKey, Integer>, Tuple2<Integer, VertexKey>,
		Tuple4<VertexKey, VertexKey, Integer, VertexKey>> {
		private static final long serialVersionUID = 1L;
		Tuple4<VertexKey, VertexKey, Integer, VertexKey> reuse = new Tuple4<VertexKey, VertexKey, Integer, VertexKey>();
		@Override
		public Tuple4<VertexKey, VertexKey, Integer, VertexKey> join(
				Tuple3<VertexKey, VertexKey, Integer> first,
				Tuple2<Integer, VertexKey> second) throws Exception {
			reuse.f0 = first.f0;
			reuse.f1 = first.f1;
			reuse.f2 = first.f2;
			reuse.f3 = second.f1;
			return reuse;
		}
	
	}
	
//	//This is in fact only a MapFunction: I use flatmap because I want to get hold of the outputcollector
//	public static class UnpackPhase1<VertexKey extends Comparable<VertexKey>, Message>
//			extends
//			RichFlatMapFunction<Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>>, 
//			Tuple3<Integer, VertexKey, Message>>
//			implements
//			ResultTypeQueryable<Tuple3<Integer, VertexKey, Message>> {
//
//		private static final long serialVersionUID = 1L;
//		private transient TypeInformation<Tuple3<Integer, VertexKey, Message>> resultType;
//
//		public UnpackPhase1(TypeInformation<VertexKey> keyType,
//				TypeInformation<Message> unPackedMessageType) {
//			this.resultType = new TupleTypeInfo<Tuple3<Integer, VertexKey, Message>>(
//					BasicTypeInfo.INT_TYPE_INFO, keyType, unPackedMessageType);
//		}
//
//		@Override
//		public TypeInformation<Tuple3<Integer, VertexKey, Message>> getProducedType() {
//			return resultType;
//		}
//
//		private Tuple3<Integer, VertexKey, Message> reuse = new Tuple3<Integer, VertexKey, Message>();
//
////		@Override
////		public Tuple3<Integer, VertexKey, Message> map(
////				Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>> value)
////				throws Exception {
////			reuse.f0 = value.f1.getChannelId();
////			reuse.f1 = value.f1.getSender();
////			reuse.f2 = value.f1.getMessage();
////			return reuse;
////		}
//
//		@Override
//		public void flatMap(
//				Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>> value,
//				Collector<Tuple3<Integer, VertexKey, Message>> out)
//				throws Exception {
//			reuse.f0 = value.f1.getChannelId();
//			reuse.f1 = value.f1.getSender();
//			reuse.f2 = value.f1.getMessage();
////			if (value.f1.getChannelId()  != 
////					getRuntimeContext().getIndexOfThisSubtask()) {
////				throw new RuntimeException("Index of subtask and storedchannelid differ");
////			}
////			if (((OutputCollector<Tuple3<Integer, VertexKey, Message>>)out).getChannel(reuse)  != 
////					getRuntimeContext().getIndexOfThisSubtask()) {
////				throw new RuntimeException("Index of subtask (" + getRuntimeContext().getIndexOfThisSubtask() + ") and channelid (" +
////						((OutputCollector<Tuple3<Integer, VertexKey, Message>>)out).getChannel(reuse) + ") differ");
////			}
////			System.out.println("don't forget me");
//
//			out.collect(reuse);
//		}
//	}

	//This is in fact only a MapFunction: I use flatmap because I want to get hold of the outputcollector
	public static class UnpackPhase1Bar<VertexKey extends Comparable<VertexKey>, Message>
			extends
			RichFlatMapFunction<Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>>, 
			Tuple4<Integer, VertexKey, Message, VertexKey>>
			implements
			ResultTypeQueryable<Tuple4<Integer, VertexKey, Message, VertexKey>> {

		private static final long serialVersionUID = 1L;
		private transient TypeInformation<Tuple4<Integer, VertexKey, Message, VertexKey>> resultType;
		private Map<Integer, VertexKey> hashKeys = new HashMap<Integer, VertexKey> (); 
		@Override
		public void open(Configuration parameters) throws Exception {
			if (getIterationRuntimeContext().getSuperstepNumber() == 1) {
				Collection<Tuple2<Integer, VertexKey>> broadcastSet = getRuntimeContext()
						.getBroadcastVariable(
								VertexCentricIteration2.HASH_KEYS_BROADCAST_SET);
				for (Tuple2<Integer, VertexKey> a : broadcastSet) {
					hashKeys.put(a.f0, a.f1);
				}
			}
		}
		
		public UnpackPhase1Bar(TypeInformation<VertexKey> keyType,
				TypeInformation<Message> unPackedMessageType) {
			this.resultType = new TupleTypeInfo<Tuple4<Integer, VertexKey, Message, VertexKey>>(
					BasicTypeInfo.INT_TYPE_INFO, keyType, unPackedMessageType, keyType);
		}

		@Override
		public TypeInformation<Tuple4<Integer, VertexKey, Message, VertexKey>> getProducedType() {
			return resultType;
		}

		private Tuple4<Integer, VertexKey, Message, VertexKey> reuse = 
				new Tuple4<Integer, VertexKey, Message, VertexKey>();


		@Override
		public void flatMap(
				Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>> value,
				Collector<Tuple4<Integer, VertexKey, Message, VertexKey>> out)
				throws Exception {
			reuse.f0 = value.f1.getChannelId();
			reuse.f1 = value.f1.getSender();
			reuse.f2 = value.f1.getMessage();
			reuse.f3 = hashKeys.get(value.f1.getChannelId());
//			if (value.f1.getChannelId()  != 
//					getRuntimeContext().getIndexOfThisSubtask()) {
//				throw new RuntimeException("Index of subtask and storedchannelid differ");
//			}
//			if (((OutputCollector<Tuple4<Integer, VertexKey, Message, VertexKey>>)out).getChannel(reuse)  != 
//					getRuntimeContext().getIndexOfThisSubtask()) {
//				throw new RuntimeException("Index of subtask (" + getRuntimeContext().getIndexOfThisSubtask() + ") and channelid (" +
//						((OutputCollector<Tuple4<Integer, VertexKey, Message, VertexKey>>)out).getChannel(reuse) + ") differ");
//			}
//			System.out.println("don't forget me");

			out.collect(reuse);
		}
	}

//	public static class ChannelIdAndSenderSelectorMsg<VertexKey, Message>
//			implements
//			KeySelector<Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>>, String> {
//		private static final long serialVersionUID = 1L;
//
//		@Override
//		public String getKey(
//				Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>> value)
//				throws Exception {
//			return MulticastUtil.getKeyFromChannelIdAndSender(
//					value.f1.getChannelId(), value.f1.getSender());
//		}
//	}
//
//	public static class UnpackMessage<VertexKey extends Comparable<VertexKey>, Message>
//			implements
//			JoinFunction<Tuple2<VertexKey, VertexKey>, Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>>, Tuple2<VertexKey, Message>>,
//			ResultTypeQueryable<Tuple2<VertexKey, Message>> {
//
//		private static final long serialVersionUID = 1L;
//		private transient TypeInformation<Tuple2<VertexKey, Message>> resultType;
//
//		private UnpackMessage(
//				TypeInformation<Tuple2<VertexKey, Message>> resultType) {
//			this.resultType = resultType;
//		}
//
//		@Override
//		public TypeInformation<Tuple2<VertexKey, Message>> getProducedType() {
//			return this.resultType;
//		}
//
//		Tuple2<VertexKey, Message> reuse = new Tuple2<VertexKey, Message>();
//
//		@Override
//		public Tuple2<VertexKey, Message> join(
//				Tuple2<VertexKey, VertexKey> first,
//				Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>> second)
//				throws Exception {
//			reuse.f0 = first.f1;
//			reuse.f1 = second.f1.getMessage();
//			return reuse;
//		}
//	}
//
//	public static class UnpackMessage2<VertexKey extends Comparable<VertexKey>, Message>
//			implements
//			JoinFunction<Tuple3<VertexKey, VertexKey, Integer>, Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>>, Tuple2<VertexKey, Message>>,
//			ResultTypeQueryable<Tuple2<VertexKey, Message>> {
//
//		private static final long serialVersionUID = 1L;
//		private transient TypeInformation<Tuple2<VertexKey, Message>> resultType;
//
//		private UnpackMessage2(
//				TypeInformation<Tuple2<VertexKey, Message>> resultType) {
//			this.resultType = resultType;
//		}
//
//		@Override
//		public TypeInformation<Tuple2<VertexKey, Message>> getProducedType() {
//			return this.resultType;
//		}
//
//		Tuple2<VertexKey, Message> reuse = new Tuple2<VertexKey, Message>();
//
//		@Override
//		public Tuple2<VertexKey, Message> join(
//				Tuple3<VertexKey, VertexKey, Integer> edgeWithPartId,
//				Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>> msgWithHeader)
//				throws Exception {
//			reuse.f0 = edgeWithPartId.f1;
//			reuse.f1 = msgWithHeader.f1.getMessage();
//			return reuse;
//		}
//	}

//	private static class UnpackMessage<VertexKey extends Comparable<VertexKey>, Message>
//			extends
//			RichFlatMapFunction<Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>>, Tuple2<VertexKey, Message>>
//			implements ResultTypeQueryable<Tuple2<VertexKey, Message>> {
//
//		private static final long serialVersionUID = 1L;
//		private transient TypeInformation<Tuple2<VertexKey, Message>> resultType;
//		private Map<VertexKey, VertexKey[]> outNeighbours;
//		private Integer partitionId;
//		private transient DataSet<Tuple3<VertexKey, VertexKey, Integer>> edgesWithChannelId;
//		
//		private static class InitUnpack<VertexKey extends Comparable<VertexKey>>
//		implements MapPartitionFunction<Tuple3<VertexKey,VertexKey,Integer>, Integer> {
//			/**
//			 * 
//			 */
//			private static final long serialVersionUID = 1L;
//			private Integer partitionId;
//			private Map<VertexKey, VertexKey[]> outNeighbours;
//			public InitUnpack(Integer partitionId,
//					Map<VertexKey, VertexKey[]> outNeighbours) {
//				this.partitionId = partitionId;
//				this.outNeighbours = outNeighbours;
//			}
//			@Override
//			public void mapPartition(
//					Iterable<Tuple3<VertexKey, VertexKey, Integer>> values,
//					Collector<Integer> out) throws Exception {
//				Iterator<Tuple3<VertexKey, VertexKey, Integer>> it = values.iterator(); 
//				Tuple3<VertexKey, VertexKey, Integer> t = it.next();
//				if (t.f2 == this.partitionId) {
//					System.out.println("hello" + t);
//					
//				}
//			}
//		}
//		
//		private UnpackMessage(DataSet<Tuple3<VertexKey, VertexKey, Integer>> edgesWithChannelId, 
//				TypeInformation<Tuple2<VertexKey, Message>> resultType) {
//			this.resultType = resultType;
//			this.outNeighbours = new HashMap<VertexKey, VertexKey[]>();
//			this.edgesWithChannelId = edgesWithChannelId;
//		}
//
//		Tuple2<VertexKey, Message> reuse = new Tuple2<VertexKey, Message>();
//
//		@Override
//		public void open(Configuration parameters) throws Exception {
//			if (getIterationRuntimeContext().getSuperstepNumber() == 1) {
//				this.partitionId = getIterationRuntimeContext()
//						.getIndexOfThisSubtask();
//				if (getRuntimeContext().getIndexOfThisSubtask() != getIterationRuntimeContext()
//						.getIndexOfThisSubtask()) {
//					throw new RuntimeException(
//							"Which of the two should I choose?");
//				}
//				System.out.println("don't forget me");
//				System.out.println(edgesWithChannelId);
//				edgesWithChannelId.mapPartition(
//						new InitUnpack(partitionId, outNeighbours)).print();
//			}
//		}
//
//		@Override
//		public void flatMap(
//				Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>> value,
//				Collector<Tuple2<VertexKey, Message>> out) throws Exception {
//			reuse.f1 = value.f1.getMessage();
//			for (VertexKey target : outNeighbours.get(value.f1.getSender())) {
//				reuse.f0 = target;
//				out.collect(reuse);
//			}
//
//		}
//
//		@Override
//		public TypeInformation<Tuple2<VertexKey, Message>> getProducedType() {
//			return this.resultType;
//		}
//	}

	public static class UnpackMessage3<VertexKey extends Comparable<VertexKey>, Message>
			implements
			JoinFunction<Tuple3<VertexKey, VertexKey, Integer>, Tuple3<Integer, VertexKey, Message>, Tuple2<VertexKey, Message>>,
			ResultTypeQueryable<Tuple2<VertexKey, Message>> {

		private static final long serialVersionUID = 1L;
		private transient TypeInformation<Tuple2<VertexKey, Message>> resultType;

		private UnpackMessage3(
				TypeInformation<Tuple2<VertexKey, Message>> resultType) {
			this.resultType = resultType;
		}

		@Override
		public TypeInformation<Tuple2<VertexKey, Message>> getProducedType() {
			return this.resultType;
		}

		private Tuple2<VertexKey, Message> reuse = new Tuple2<VertexKey, Message>();

		@Override
		public Tuple2<VertexKey, Message> join(
				Tuple3<VertexKey, VertexKey, Integer> edgeWithPartId,
				Tuple3<Integer, VertexKey, Message> msgWithHeader)
				throws Exception {
			reuse.f0 = edgeWithPartId.f1;
			reuse.f1 = msgWithHeader.f2;
			return reuse;
		}
	}

	public static class UnpackMessageCoGroup<VertexKey extends Comparable<VertexKey>, Message>
			extends
			RichCoGroupFunction<Tuple4<VertexKey, VertexKey, Integer, VertexKey>, 
				Tuple4<Integer, VertexKey, Message, VertexKey>, 
				Tuple2<VertexKey, Message>>
			implements
			ResultTypeQueryable<Tuple2<VertexKey, Message>> {

		private static final long serialVersionUID = 1L;
		private transient TypeInformation<Tuple2<VertexKey, Message>> resultType;
		
		private UnpackMessageCoGroup(
				TypeInformation<Tuple2<VertexKey, Message>> resultType) {
			this.resultType = resultType;
		}
		@Override
		public TypeInformation<Tuple2<VertexKey, Message>> getProducedType() {
			return this.resultType;
		}

		private Tuple2<VertexKey, Message> reuse = new Tuple2<VertexKey, Message>();

		private Map<VertexKey, List<VertexKey>> outNeighboursInThisPart = 
				new HashMap<VertexKey, List<VertexKey>>();
		
//		@Override
//		public void coGroup(
//				Iterable<Tuple3<VertexKey, VertexKey, Integer>> edgesInPart,
//				Iterable<Tuple3<Integer, VertexKey, Message>> messages,
//				Collector<Tuple2<VertexKey, Message>> out) throws Exception {
//			if (getIterationRuntimeContext().getSuperstepNumber() == 1) {
//				//read outneighbours into memory
//				for (Tuple3<VertexKey, VertexKey, Integer> edge: edgesInPart) {
//					VertexKey source = edge.f0;
//					VertexKey target = edge.f1;
//					if (!outNeighboursInThisPart.containsKey(source)) {
//						outNeighboursInThisPart.put(source, new ArrayList<VertexKey>());
//					}
//					outNeighboursInThisPart.get(source).add(target);
//				}
////				System.out.println("Subtask: " + getIterationRuntimeContext().getIndexOfThisSubtask());
////				System.out.println(outNeighboursInThisPart);
//			}
//			for (Tuple3<Integer, VertexKey, Message> m: messages) {
//				reuse.f1 = m.f2;
//				VertexKey sender = m.f1;
//				for (VertexKey recipient: outNeighboursInThisPart.get(sender)) {
//					reuse.f0 = recipient;
//					out.collect(reuse);
//				}
//			}
//		}
		@Override
		public void coGroup(
				Iterable<Tuple4<VertexKey, VertexKey, Integer, VertexKey>> edgesInPart,
				Iterable<Tuple4<Integer, VertexKey, Message, VertexKey>> messages,
				Collector<Tuple2<VertexKey, Message>> out) throws Exception {
			if (getIterationRuntimeContext().getSuperstepNumber() == 1) {
				//read outneighbours into memory
				for (Tuple4<VertexKey, VertexKey, Integer, VertexKey> edge: edgesInPart) {
					VertexKey source = edge.f0;
					VertexKey target = edge.f1;
					if (!outNeighboursInThisPart.containsKey(source)) {
						outNeighboursInThisPart.put(source, new ArrayList<VertexKey>());
					}
					outNeighboursInThisPart.get(source).add(target);
				}
//				System.out.println("Subtask: " + getIterationRuntimeContext().getIndexOfThisSubtask());
//				System.out.println(outNeighboursInThisPart);
			}
			for (Tuple4<Integer, VertexKey, Message, VertexKey> m: messages) {
				reuse.f1 = m.f2;
				VertexKey sender = m.f1;
				for (VertexKey recipient: outNeighboursInThisPart.get(sender)) {
					reuse.f0 = recipient;
					out.collect(reuse);
				}
			}
			
		}
	}

	// public static class ChannelIdAdder<VertexKey extends
	// Comparable<VertexKey>>
	// extends
	// RichMapPartitionFunction<Tuple2<VertexKey, VertexKey>, Tuple3<VertexKey,
	// VertexKey, Integer>> {
	// private static final long serialVersionUID = 1L;
	// Tuple3<VertexKey, VertexKey, Integer> reuse = new Tuple3<VertexKey,
	// VertexKey, Integer>();
	//
	// @Override
	// public void mapPartition(Iterable<Tuple2<VertexKey, VertexKey>> values,
	// Collector<Tuple3<VertexKey, VertexKey, Integer>> out)
	// throws Exception {
	// System.out.println("ChannelIdAdder");
	// for (Tuple2<VertexKey, VertexKey> edge : values) {
	// reuse.f0 = edge.f0;
	// reuse.f1 = edge.f1;
	// reuse.f2 = getRuntimeContext().getIndexOfThisSubtask();
	// out.collect(reuse);
	// }
	// }
	// }

	public static class SubtaskIndexAdder<VertexKey extends Comparable<VertexKey>>
			extends
			RichMapFunction<Tuple2<VertexKey, VertexKey>, Tuple3<VertexKey, VertexKey, Integer>> {
		private static final long serialVersionUID = 1L;
		Tuple3<VertexKey, VertexKey, Integer> reuse = new Tuple3<VertexKey, VertexKey, Integer>();

		@Override
		public Tuple3<VertexKey, VertexKey, Integer> map(
				Tuple2<VertexKey, VertexKey> value) throws Exception {
			reuse.f0 = value.f0;
			reuse.f1 = value.f1;
			reuse.f2 = getRuntimeContext().getIndexOfThisSubtask();
			return reuse;
		}
	}

	// --------------------------------------------------------------------------------------------
	// Constructor builders to avoid signature conflicts with generic type
	// erasure
	// --------------------------------------------------------------------------------------------

	/**
	 * Creates a new vertex-centric iteration operator for graphs where the
	 * edges are not associated with a value.
	 * 
	 * @param edgesWithoutValue
	 *            The data set containing edges. Edges are represented as
	 *            2-tuples: (source-id, target-id)
	 * @param vertexUpdateFunction
	 *            The function that updates the state of the vertices from the
	 *            incoming messages.
	 * @param messagingFunction
	 *            The function that turns changed vertex states into messages
	 *            along the edges.
	 * 
	 * @param <VertexKey>
	 *            The type of the vertex key (the vertex identifier).
	 * @param <VertexValue>
	 *            The type of the vertex value (the state of the vertex).
	 * @param <Message>
	 *            The type of the message sent between vertices along the edges.
	 * 
	 * @return An in stance of the vertex-centric graph computation operator.
	 */
	public static final <VertexKey extends Comparable<VertexKey>, VertexValue, Message> VertexCentricIteration2<VertexKey, VertexValue, Message, ?> withPlainEdges(
			DataSet<Tuple2<VertexKey, VertexKey>> edgesWithoutValue,
			VertexUpdateFunction<VertexKey, VertexValue, Message> vertexUpdateFunction,
			MessagingFunction2<VertexKey, VertexValue, Message, ?> messagingFunction,
			int maximumNumberOfIterations) {
		@SuppressWarnings("unchecked")
		MessagingFunction2<VertexKey, VertexValue, Message, Object> tmf = (MessagingFunction2<VertexKey, VertexValue, Message, Object>) messagingFunction;

		return new VertexCentricIteration2<VertexKey, VertexValue, Message, Object>(
				vertexUpdateFunction, tmf, edgesWithoutValue,
				maximumNumberOfIterations);
	}

	// --------------------------------------------------------------------------------------------
	// Wrapping UDFs
	// --------------------------------------------------------------------------------------------

	private static final class VertexUpdateUdf<VertexKey extends Comparable<VertexKey>, VertexValue, Message>
			extends
			RichCoGroupFunction<Tuple2<VertexKey, Message>, Tuple2<VertexKey, VertexValue>, Tuple2<VertexKey, VertexValue>>
			implements ResultTypeQueryable<Tuple2<VertexKey, VertexValue>> {
		private static final long serialVersionUID = 1L;

		private final VertexUpdateFunction<VertexKey, VertexValue, Message> vertexUpdateFunction;

		private final MessageIterator<Message> messageIter = new MessageIterator<Message>();

		private transient TypeInformation<Tuple2<VertexKey, VertexValue>> resultType;

		private VertexUpdateUdf(
				VertexUpdateFunction<VertexKey, VertexValue, Message> vertexUpdateFunction,
				TypeInformation<Tuple2<VertexKey, VertexValue>> resultType) {
			this.vertexUpdateFunction = vertexUpdateFunction;
			this.resultType = resultType;
		}

		@Override
		public void coGroup(Iterable<Tuple2<VertexKey, Message>> messages,
				Iterable<Tuple2<VertexKey, VertexValue>> vertex,
				Collector<Tuple2<VertexKey, VertexValue>> out) throws Exception {
			final Iterator<Tuple2<VertexKey, VertexValue>> vertexIter = vertex
					.iterator();

			if (vertexIter.hasNext()) {
				Tuple2<VertexKey, VertexValue> vertexState = vertexIter.next();

				@SuppressWarnings("unchecked")
				Iterator<Tuple2<?, Message>> downcastIter = (Iterator<Tuple2<?, Message>>) (Iterator<?>) messages
						.iterator();

				messageIter.setSource(downcastIter);

				vertexUpdateFunction.setOutput(vertexState, out);
				vertexUpdateFunction.updateVertex(vertexState.f0,
						vertexState.f1, messageIter);
			} else {
				final Iterator<Tuple2<VertexKey, Message>> messageIter = messages
						.iterator();
				if (messageIter.hasNext()) {
					String message = "Target vertex does not exist!.";
					try {
						Tuple2<VertexKey, Message> next = messageIter.next();
						message = "Target vertex '" + next.f0
								+ "' does not exist!.";
					} catch (Throwable t) {
					}
					throw new Exception(message);
				} else {
					throw new Exception();
				}
			}
		}

		@Override
		public void open(Configuration parameters) throws Exception {
			if (getIterationRuntimeContext().getSuperstepNumber() == 1) {
				this.vertexUpdateFunction.init(getIterationRuntimeContext());
			}
			this.vertexUpdateFunction.preSuperstep();
		}

		@Override
		public void close() throws Exception {
			this.vertexUpdateFunction.postSuperstep();
		}

		@Override
		public TypeInformation<Tuple2<VertexKey, VertexValue>> getProducedType() {
			return this.resultType;
		}

	}

	/*
	 * UDF that encapsulates the message sending function for graphs where the
	 * edges have no associated values.
	 */
	private static final class MessagingUdfNoEdgeValues<VertexKey extends Comparable<VertexKey>, VertexValue, Message>
			extends
			RichCoGroupFunction<Tuple2<VertexKey, VertexKey>, Tuple2<VertexKey, VertexValue>, Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>>>
			implements
			ResultTypeQueryable<Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>>> {
		private static final long serialVersionUID = 1L;

		private final MessagingFunction2<VertexKey, VertexValue, Message, ?> messagingFunction;

		private transient TypeInformation<Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>>> resultType;

		private MessagingUdfNoEdgeValues(
				MessagingFunction2<VertexKey, VertexValue, Message, ?> messagingFunction,
				TypeInformation<Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>>> resultType) {
			this.messagingFunction = messagingFunction;
			this.resultType = resultType;
		}

		@Override
		public void coGroup(
				Iterable<Tuple2<VertexKey, VertexKey>> edges,
				Iterable<Tuple2<VertexKey, VertexValue>> state,
				Collector<Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>>> out)
				throws Exception {
			final Iterator<Tuple2<VertexKey, VertexValue>> stateIter = state
					.iterator();

			if (stateIter.hasNext()) {
				Tuple2<VertexKey, VertexValue> newVertexState = stateIter
						.next();

				messagingFunction.set((Iterator<?>) edges.iterator(), out);
				messagingFunction.setSender(newVertexState.f0);
				messagingFunction.sendMessages(newVertexState.f0,
						newVertexState.f1);
			}
		}

		@Override
		public void open(Configuration parameters) throws Exception {
			if (getIterationRuntimeContext().getSuperstepNumber() == 1) {
				this.messagingFunction
						.init(getIterationRuntimeContext(), false);
			}

			this.messagingFunction.preSuperstep();
		}

		@Override
		public void close() throws Exception {
			this.messagingFunction.postSuperstep();
		}

		@Override
		public TypeInformation<Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>>> getProducedType() {
			return this.resultType;
		}
	}

	/*
	 * UDF that encapsulates the message sending function for graphs where the
	 * edges have an associated value.
	 */
	private static final class MessagingUdfWithEdgeValues<VertexKey extends Comparable<VertexKey>, VertexValue, Message, EdgeValue>
			extends
			RichCoGroupFunction<Tuple3<VertexKey, VertexKey, EdgeValue>, Tuple2<VertexKey, VertexValue>, Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>>>
			implements
			ResultTypeQueryable<Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>>> {
		private static final long serialVersionUID = 1L;

		private final MessagingFunction2<VertexKey, VertexValue, Message, EdgeValue> messagingFunction;

		private transient TypeInformation<Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>>> resultType;

		private MessagingUdfWithEdgeValues(
				MessagingFunction2<VertexKey, VertexValue, Message, EdgeValue> messagingFunction,
				TypeInformation<Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>>> resultType) {
			this.messagingFunction = messagingFunction;
			this.resultType = resultType;
		}

		@Override
		public void open(Configuration parameters) throws Exception {
			if (getIterationRuntimeContext().getSuperstepNumber() == 1) {
				this.messagingFunction.init(getIterationRuntimeContext(), true);
			}

			this.messagingFunction.preSuperstep();
		}

		@Override
		public void close() throws Exception {
			this.messagingFunction.postSuperstep();
		}

		@Override
		public TypeInformation<Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>>> getProducedType() {
			return this.resultType;
		}

		@Override
		public void coGroup(
				Iterable<Tuple3<VertexKey, VertexKey, EdgeValue>> edges,
				Iterable<Tuple2<VertexKey, VertexValue>> state,
				Collector<Tuple2<VertexKey, MessageWithHeader<VertexKey, Message>>> out)
				throws Exception {
			final Iterator<Tuple2<VertexKey, VertexValue>> stateIter = state
					.iterator();

			if (stateIter.hasNext()) {
				Tuple2<VertexKey, VertexValue> newVertexState = stateIter
						.next();

				messagingFunction.set((Iterator<?>) edges.iterator(), out);
				messagingFunction.setSender(newVertexState.f0);
				messagingFunction.sendMessages(newVertexState.f0,
						newVertexState.f1);
			}

		}
	}
}
