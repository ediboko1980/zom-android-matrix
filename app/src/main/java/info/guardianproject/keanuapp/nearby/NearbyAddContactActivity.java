package info.guardianproject.keanuapp.nearby;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

import info.guardianproject.keanu.core.model.Contact;
import info.guardianproject.keanu.core.model.Presence;
import info.guardianproject.keanu.core.model.impl.BaseAddress;
import info.guardianproject.keanu.core.provider.Imps;

import java.util.ArrayList;
import java.util.HashMap;

import info.guardianproject.keanuapp.R;
import info.guardianproject.keanuapp.ImApp;
import info.guardianproject.keanuapp.tasks.AddContactAsyncTask;
import info.guardianproject.keanuapp.ui.contacts.ContactListItem;
import info.guardianproject.keanuapp.ui.contacts.ContactViewHolder;
import info.guardianproject.keanuapp.ui.onboarding.OnboardingManager;

import static android.content.Intent.EXTRA_TEXT;
import static info.guardianproject.keanu.core.KeanuConstants.LOG_TAG;

public class NearbyAddContactActivity extends AppCompatActivity {

  //  private MessageListener mMessageListener;
 //   private Message mMessage;
    private static ImApp mApp;
    private static RecyclerView mList;
    private static HashMap<String,Contact> contactList = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nearby);

        setTitle("");

        ImageView iv = (ImageView) findViewById(R.id.nearbyIcon);
        mList = findViewById(R.id.nearbyList);
        mList.setLayoutManager(new LinearLayoutManager(this));
        mList.setItemAnimator(new DefaultItemAnimator());

        NearbyListRecyclerViewAdapter adapter = new NearbyListRecyclerViewAdapter(this,new ArrayList<Contact>(contactList.values()));
        mList.setAdapter(adapter);

        ObjectAnimator scaleDown = ObjectAnimator.ofPropertyValuesHolder(
                iv,
                PropertyValuesHolder.ofFloat("scaleX", 1.2f),
                PropertyValuesHolder.ofFloat("scaleY", 1.2f));
        scaleDown.setDuration(310);
        scaleDown.setInterpolator(new FastOutSlowInInterpolator());

        scaleDown.setRepeatCount(ObjectAnimator.INFINITE);
        scaleDown.setRepeatMode(ObjectAnimator.REVERSE);

        scaleDown.start();

        mApp = (ImApp)getApplication();

        if (getSupportActionBar() != null)
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        // Showing status
        if(checkPlayServices())
        {
            String nearbyMessage = getIntent().getStringExtra(EXTRA_TEXT);
            initNearby(nearbyMessage);
        }
        else
        {
            Toast.makeText(this, R.string.nearby_not_supported,Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private final static int PLAY_SERVICES_RESOLUTION_REQUEST = 2404;

    private boolean checkPlayServices() {
        GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
        int resultCode = apiAvailability.isGooglePlayServicesAvailable(this);
        if (resultCode != ConnectionResult.SUCCESS) {
            if (apiAvailability.isUserResolvableError(resultCode)) {
                apiAvailability.getErrorDialog(this, resultCode, PLAY_SERVICES_RESOLUTION_REQUEST)
                        .show();
            } else {
                Log.i(LOG_TAG, "This device is not supported.");
                finish();
            }
            return false;
        }
        return true;
    }

    private void initNearby (String nearbyMessage) {

        /**
        mMessageListener = new MessageListener() {
            @Override
            public void onFound(Message message) {
              //  Log.d(TAG, "Found message: " + new String(message.getContent()));

                OnboardingManager.DecodedInviteLink diLink = OnboardingManager.decodeInviteLink(new String(message.getContent()));

                if (diLink != null) {
                        addContact(diLink);
                }
            }

            @Override
            public void onLost(Message message) {
                Log.d(LOG_TAG, "Lost sight of message: " + new String(message.getContent()));
            }
        };

        mMessage = new Message(nearbyMessage.getBytes());**/
    }

    private void addContact (OnboardingManager.DecodedInviteLink diLink)
    {
        Contact contact = new Contact(new BaseAddress(diLink.username));
        contact.setName(diLink.nickname);
        contact.setPresence(new Presence());
        contact.setSubscriptionType(Imps.ContactsColumns.SUBSCRIPTION_TYPE_FROM);
        contact.setSubscriptionStatus(Imps.ContactsColumns.SUBSCRIPTION_STATUS_SUBSCRIBE_PENDING);
        contactList.put(contact.getAddress().getAddress(),contact);

        refreshList();  
    }

    private static void ignoreContact (Contact contact)
    {
        contactList.remove(contact);
        refreshList();
    }

    private static void confirmContact (Contact contact)
    {
        new AddContactAsyncTask(mApp.getDefaultProviderId(), mApp.getDefaultAccountId()).execute(contact.getAddress().getAddress(), "", contact.getName());
        contact.setSubscriptionType(Imps.ContactsColumns.SUBSCRIPTION_TYPE_BOTH);
        contactList.remove(contact);
        refreshList();
    }

    private static void refreshList ()
    {
        NearbyListRecyclerViewAdapter adapter = new NearbyListRecyclerViewAdapter(mApp,new ArrayList<Contact>(contactList.values()));
        mList.setAdapter(adapter);
    }

    @Override
    public void onStart() {
        super.onStart();

    //    Nearby.getMessagesClient(this).publish(mMessage);
     //   Nearby.getMessagesClient(this).subscribe(mMessageListener);
    }

    @Override
    public void onStop() {
      //  Nearby.getMessagesClient(this).unpublish(mMessage);
      //  Nearby.getMessagesClient(this).unsubscribe(mMessageListener);

        super.onStop();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        if (item.getItemId() == android.R.id.home)
            finish();

        return super.onOptionsItemSelected(item);
    }

    public static class NearbyListRecyclerViewAdapter
            extends RecyclerView.Adapter<ContactViewHolder> {

        private final TypedValue mTypedValue = new TypedValue();
        private int mBackground;
        private Context mContext;
        private  ArrayList<Contact> mItemList;

        public NearbyListRecyclerViewAdapter(Context context,  ArrayList<Contact> itemList) {
            super();

            mItemList = itemList;
            context.getTheme().resolveAttribute(R.attr.selectableItemBackground, mTypedValue, true);
            mBackground = mTypedValue.resourceId;
            mContext = context;

        }


        @Override
        public ContactViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {


            ContactListItem view = (ContactListItem) LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.contact_view, parent, false);
            view.setShowPresence(false);
            view.setBackgroundResource(mBackground);

            ContactViewHolder holder = view.getViewHolder();
            if (holder == null) {
                holder = new ContactViewHolder(view);
                view.applyStyleColors(holder);

                view.setViewHolder(holder);
            }

            return holder;

        }

        @Override
        public void onBindViewHolder(@NonNull ContactViewHolder viewHolder, final int position) {

            final Contact contact = mItemList.get(position);

            viewHolder.mContactId =  -1;
            viewHolder.mAddress =  contact.getAddress().getBareAddress();
            viewHolder.mType = Imps.ContactsColumns.TYPE_NORMAL;

            String nickname = contact.getName();

            viewHolder.mNickname = nickname;

            if (viewHolder.itemView instanceof ContactListItem) {
               ((ContactListItem)viewHolder.itemView).bind(viewHolder, contact, new View.OnClickListener() {
                   @Override
                   public void onClick(View view) {
                        NearbyAddContactActivity.confirmContact(contact);

                   }
               }, new View.OnClickListener() {
                   @Override
                   public void onClick(View view) {
                        NearbyAddContactActivity.ignoreContact(contact);
                   }
               });
            }

            viewHolder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {



                }
            });

        }

        @Override
        public int getItemCount() {
            return mItemList.size();
        }


    }
}
